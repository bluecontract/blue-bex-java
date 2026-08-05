package blue.bex.conformance;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Generates a deterministic descriptor-level manifest of the public and
 * protected binary API physically present in the packaged BEX JAR.
 */
public final class BexBinaryApiManifestMain {
    private BexBinaryApiManifestMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Expected packaged JAR and output manifest paths");
        }
        Path artifact = Paths.get(args[0])
                .toAbsolutePath().normalize();
        Path output = Paths.get(args[1])
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException(
                    "Packaged JAR is missing: " + artifact);
        }

        List<String> classes = publicClassNames(artifact);
        List<String> manifest = new ArrayList<String>();
        manifest.add("schema=blue-bex-binary-api-manifest/1.0");
        try (JarFirstClassLoader loader =
                new JarFirstClassLoader(
                        artifact.toUri().toURL(),
                        BexBinaryApiManifestMain.class.getClassLoader())) {
            for (String className : classes) {
                Class<?> type =
                        Class.forName(className, false, loader);
                if (!isApi(type.getModifiers())) {
                    continue;
                }
                manifest.add(classSignature(type));
                List<String> members =
                        new ArrayList<String>();
                for (Field field : type.getDeclaredFields()) {
                    if (isApi(field.getModifiers())) {
                        members.add(fieldSignature(field));
                    }
                }
                for (Constructor<?> constructor
                        : type.getDeclaredConstructors()) {
                    if (isApi(constructor.getModifiers())) {
                        members.add(constructorSignature(constructor));
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (isApi(method.getModifiers())) {
                        members.add(methodSignature(method));
                    }
                }
                Collections.sort(members);
                manifest.addAll(members);
            }
        }
        Files.createDirectories(output.getParent());
        Files.write(
                output,
                (joinLines(manifest) + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        System.out.println("BEX binary API manifest: " + output);
    }

    private static List<String> publicClassNames(Path artifact)
            throws IOException {
        List<String> result = new ArrayList<String>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("blue/bex/")
                        && name.endsWith(".class")
                        && !name.equals("module-info.class")) {
                    result.add(name.substring(0, name.length() - 6)
                            .replace('/', '.'));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    private static boolean isApi(int modifiers) {
        return Modifier.isPublic(modifiers)
                || Modifier.isProtected(modifiers);
    }

    private static String classSignature(Class<?> type) {
        StringBuilder result = new StringBuilder();
        result.append("class ")
                .append(Modifier.toString(type.getModifiers()))
                .append(' ')
                .append(type.getName());
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            result.append(" extends ")
                    .append(typeName(superclass));
        }
        List<String> interfaces = new ArrayList<String>();
        for (Class<?> implemented : type.getInterfaces()) {
            interfaces.add(typeName(implemented));
        }
        Collections.sort(interfaces);
        if (!interfaces.isEmpty()) {
            result.append(" implements ")
                    .append(String.join(",", interfaces));
        }
        return result.toString();
    }

    private static String fieldSignature(Field field) {
        return "  field "
                + Modifier.toString(field.getModifiers())
                + " " + field.getName()
                + ":" + typeName(field.getType())
                + flags(field);
    }

    private static String constructorSignature(
            Constructor<?> constructor) {
        return "  constructor "
                + Modifier.toString(constructor.getModifiers())
                + " <init>("
                + parameterTypes(constructor.getParameterTypes())
                + ")"
                + exceptionClause(constructor.getExceptionTypes())
                + flags(constructor);
    }

    private static String methodSignature(Method method) {
        return "  method "
                + Modifier.toString(method.getModifiers())
                + " " + method.getName()
                + "(" + parameterTypes(method.getParameterTypes()) + ")"
                + ":" + typeName(method.getReturnType())
                + exceptionClause(method.getExceptionTypes())
                + flags(method);
    }

    private static String parameterTypes(Class<?>[] types) {
        List<String> values = new ArrayList<String>();
        for (Class<?> type : types) {
            values.add(typeName(type));
        }
        return String.join(",", values);
    }

    private static String exceptionTypes(Class<?>[] types) {
        List<String> values = new ArrayList<String>();
        for (Class<?> type : types) {
            values.add(typeName(type));
        }
        Collections.sort(values);
        return String.join(",", values);
    }

    private static String exceptionClause(Class<?>[] types) {
        String exceptions = exceptionTypes(types);
        return exceptions.isEmpty()
                ? ""
                : " throws " + exceptions;
    }

    private static String typeName(Class<?> type) {
        if (!type.isArray()) {
            return type.getName();
        }
        return typeName(type.getComponentType()) + "[]";
    }

    private static String flags(Member member) {
        StringBuilder flags = new StringBuilder();
        if (member.isSynthetic()) {
            flags.append(" synthetic");
        }
        if (member instanceof Method
                && ((Method) member).isBridge()) {
            flags.append(" bridge");
        }
        return flags.toString();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result.append('\n');
            }
            result.append(lines.get(index));
        }
        return result.toString();
    }

    private static final class JarFirstClassLoader
            extends URLClassLoader {
        JarFirstClassLoader(URL artifact, ClassLoader parent) {
            super(new URL[] {artifact}, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(
                String name,
                boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("blue.bex.")) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInArtifact) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
}
