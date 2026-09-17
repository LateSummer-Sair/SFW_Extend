package sair.v4.hot;

import java.util.Map;

/** 每个技能一个自己的类加载器（不同技能的 entry 类可以同名，互不冲突）。 */
final class SkClassLoader extends ClassLoader {

    private final Map<String, byte[]> defs;

    SkClassLoader(Map<String, byte[]> defs, ClassLoader parent) {
        super(parent);
        this.defs = defs;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = defs.get(name);
        if (b == null) throw new ClassNotFoundException(name);
        return defineClass(name, b, 0, b.length);
    }
}
