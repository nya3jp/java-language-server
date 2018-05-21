package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

public class JavaPresentationCompiler {
    private static final Logger LOG = Logger.getLogger("main");

    // Not modifiable! If you want to edit these, you need to create a new instance
    private final Set<Path> sourcePath, classPath;
    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    private final StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(this::report, null, Charset.defaultCharset());
    // Cache a single compiled file
    // Since the user can only edit one file at a time, this should be sufficient
    private Cache cache;

    public JavaPresentationCompiler(Set<Path> sourcePath, Set<Path> classPath) {
        // sourcePath and classPath can't actually be modified, because JavaCompiler remembers them from task to task
        this.sourcePath = Collections.unmodifiableSet(sourcePath);
        this.classPath = Collections.unmodifiableSet(classPath);
    }

    private void report(Diagnostic<? extends JavaFileObject> diags) {
        LOG.warning(diags.getMessage(null));
    }

    /**
     * Combine source path or class path entries using the system separator, for example ':' in unix
     */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath
                .stream()
                .map(p -> p.toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<Path> sourcePath, Set<Path> classPath) {
        return Arrays.asList(
                "-classpath",
                joinPath(classPath),
                "-sourcepath",
                joinPath(sourcePath),
                "-verbose",
                "-proc:none",
                "-g",
                // You would think we could do -Xlint:all,
                // but some lints trigger fatal errors in the presence of parse errors
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
    }

    /** Create a task that compiles a single file */
    private JavacTask singleFileTask(URI file, String contents) {
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        JavaPresentationCompiler.this::report,
                        options(sourcePath, classPath),
                        Collections.emptyList(),
                        Collections.singletonList(new StringFileObject(contents, file)));
    }

    /** Stores the compiled version of a single file */
    class Cache {
        final String contents;
        final URI file;
        final CompilationUnitTree root;
        final JavacTask task;
        // Only the block around the cursor is focused
        // All other blocks have their contents erased to speed up compilation
        final long focusStart, focusEnd;

        Cache(URI file, String contents, int line, int character) {
            // If `line` is -1, recompile the entire file
            if (line == -1) {
                this.contents = contents;
                this.focusStart = 0;
                this.focusEnd = contents.length();
            }
            // Otherwise, focus on the block surrounding line:character, erasing all other block bodies
            else {
                Pruner p = new Pruner(file, contents);
                p.prune(line, character);
                this.contents = p.contents();
                this.focusStart = p.focusStart();
                this.focusEnd = p.focusEnd();
            }
            this.file = file;
            this.task = singleFileTask(file, contents);
            try {
                this.root = task.parse().iterator().next();
                // The results of task.analyze() are unreliable when errors are present
                // You can get at `Element` values using `Trees`
                task.analyze();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Is line:character contained in the focused block that was actually compiled? All other
         * blocks were erased; you should re-compile if you need information from another block.
         */
        boolean focusIncludes(int line, int character) {
            long p = root.getLineMap().getPosition(line, character);
            return focusStart <= p && p <= focusEnd;
        }
    }

    /** Recompile if the active file has been edited, or if the active file has changed */
    private void recompile(URI file, String contents, int line, int character) {
        if (cache == null
                || !cache.file.equals(file)
                || !cache.contents.equals(contents)
                || !cache.focusIncludes(line, character)) {
            cache = new Cache(file, contents, line, character);
        }
    }

    private static <T> T TODO() {
        throw new UnsupportedOperationException("TODO");
    }

    /** Find the smallest tree that includes the cursor */
    private TreePath path(URI file, String contents, int line, int character) {
        Trees trees = Trees.instance(cache.task);
        SourcePositions pos = trees.getSourcePositions();
        long cursor = cache.root.getLineMap().getPosition(line, character);

        // Search for the smallest element that encompasses line:column
        class FindSmallest extends TreeScanner<Void, Void> {
            Tree found = null;

            boolean containsCursor(Tree tree) {
                long start = pos.getStartPosition(cache.root, tree),
                        end = pos.getEndPosition(cache.root, tree);
                // If element has no position, give up
                if (start == -1 || end == -1) return false;
                // Check if `tree` contains line:column
                return start <= cursor && cursor <= end;
            }

            @Override
            public Void scan(Tree tree, Void nothing) {
                // This is pre-order traversal, so the deepest element will be the last one remaining in `found`
                if (containsCursor(tree)) found = tree;
                super.scan(tree, nothing);
                return null;
            }

            Optional<Tree> find(Tree root) {
                scan(root, null);
                return Optional.ofNullable(found);
            }
        }
        Tree found =
                new FindSmallest()
                        .find(cache.root)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                String.format(
                                                        "No TreePath to %s %d:%d",
                                                        file, line, character)));

        return trees.getPath(cache.root, found);
    }

    /** Find all identifiers accessible from scope at line:character */
    public List<Element> scopeMembers(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        TreePath path = path(file, contents, line, character);
        Scope start = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            boolean isThisOrSuper(VariableElement ve) {
                String name = ve.getSimpleName().toString();
                return name.equals("this") || name.equals("super");
            }

            // Place each member of `this` or `super` directly into `results`
            void unwrapThisSuper(VariableElement ve) {
                TypeMirror thisType = ve.asType();
                // `this` and `super` should always be instances of DeclaredType, which we'll use to check accessibility
                if (!(thisType instanceof DeclaredType)) {
                    LOG.warning(String.format("%s is not a DeclaredType", thisType));
                    return;
                }
                DeclaredType thisDeclaredType = (DeclaredType) thisType;
                Element thisElement = types.asElement(thisDeclaredType);
                for (Element thisMember : thisElement.getEnclosedElements()) {
                    // Check if member is accessible from original scope
                    if (trees.isAccessible(start, thisMember, thisDeclaredType)) {
                        result.add(thisMember);
                    }
                }
            }

            // Place each member of `s` into results, and unwrap `this` and `super`
            void walkLocals(Scope s) {
                for (Element e : s.getLocalElements()) {
                    if (e instanceof TypeElement) {
                        TypeElement te = (TypeElement) e;
                        if (trees.isAccessible(start, te)) result.add(te);
                    } else if (e instanceof VariableElement) {
                        VariableElement ve = (VariableElement) e;
                        result.add(ve);
                        if (isThisOrSuper(ve)) {
                            unwrapThisSuper(ve);
                        }
                    } else {
                        result.add(e);
                    }
                }
            }

            // Walk each enclosing scope, placing its members into `results`
            List<Element> walkScopes() {
                for (Scope s = start; s != null; s = s.getEnclosingScope()) {
                    walkLocals(s);
                }

                return result;
            }
        }
        return new Walk().walkScopes();
    }

    /** Find all members of expression ending at line:character */
    public List<Element> members(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        Elements elements = cache.task.getElements();
        TreePath path = path(file, contents, line, character);
        Scope scope = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            // Place each member of `t` into `results`
            void walkType(TypeMirror t) {
                Element e = types.asElement(t);
                for (Element member : e.getEnclosedElements()) {
                    // If type is a DeclaredType, check accessibility of member
                    if (t instanceof DeclaredType) {
                        DeclaredType declaredType = (DeclaredType) t;
                        if (trees.isAccessible(scope, member, declaredType)) {
                            result.add(member);
                        }
                    }
                    // Otherwise, accessibility rules are very complicated
                    // Give up and just declare that everything is accessible
                    else result.add(member);
                }
            }

            // Walk the type at `path` and each of its direct supertypes, placing members into `results`
            List<Element> walkSupers() {
                TypeMirror t = trees.getTypeMirror(path);
                // Add all the direct members first
                walkType(t);
                // Add members of superclasses and interfaces
                for (TypeMirror s : types.directSupertypes(t)) {
                    walkType(s);
                }
                // Object type is not included by default
                // We need to add it to get members like .equals(other) and .hashCode()
                // TODO this may add things twice for interfaces with no super-interfaces
                walkType(elements.getTypeElement("java.lang.Object").asType());

                return result;
            }
        }
        return new Walk().walkSupers();
    }

    /** Find the smallest element that includes the cursor */
    public Element element(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, contents, line, character);
        return trees.getElement(path);
    }
}
