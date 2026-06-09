package sair.sacoms.jni;

import java.io.File;

/**
 * JNI bridge for SaComS_LibC native library.
 * <p>
 * This class provides native-backed implementations of core SaComS utilities.
 * The native library (SaComS_LibC.dll/.so) must be compiled from the C
 * sources in this project and placed where SFW can find it.
 * </p>
 *
 * <p>Library search order:</p>
 * <ol>
 *   <li>{@code sair.sacoms.jni.lib.path} system property</li>
 *   <li>{@code plugins/native/SaComS_LibC.dll} relative to SFW install dir</li>
 *   <li>{@code java.library.path}</li>
 * </ol>
 *
 * @author Generated for SairFrameWork
 */
public final class JNISaComS {

    private static volatile boolean loaded = false;
    private static volatile String loadError = null;

    /* ---- Static initializer — load native library ---- */

    static {
        tryLoadNative();
    }

    private static void tryLoadNative() {
        if (loaded) return;

        String libName = "SaComS_LibC";
        String osName = System.getProperty("os.name", "").toLowerCase();
        String libExt  = osName.contains("win") ? ".dll" : ".so";

        /* 1. system property override */
        String propPath = System.getProperty("sair.sacoms.jni.lib.path");
        if (propPath != null && !propPath.isEmpty()) {
            try {
                System.load(new File(propPath).getAbsolutePath());
                loaded = true;
                return;
            } catch (UnsatisfiedLinkError e) {
                loadError = e.getMessage();
            }
        }

        /* 2. resolve relative to SFW install (from JAR location) */
        try {
            String classPath = JNISaComS.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            /* Navigate up from the jar location assuming SFW structure */
            File jarFile = new File(classPath);
            File sfrRoot = jarFile.getParentFile(); /* plugins/modlib */
            if (sfrRoot != null) {
                sfrRoot = sfrRoot.getParentFile(); /* plugins */
                if (sfrRoot != null) {
                    File nativeDir = new File(sfrRoot, "native");
                    if (!nativeDir.exists()) nativeDir.mkdirs();
                    File libFile = new File(nativeDir, libName + libExt);
                    if (libFile.exists()) {
                        System.load(libFile.getAbsolutePath());
                        loaded = true;
                        return;
                    }
                    /* also try native dir at SFW root */
                    sfrRoot = sfrRoot.getParentFile();  /* SFW root */
                    if (sfrRoot != null) {
                        nativeDir = new File(sfrRoot, "plugins/native");
                        libFile = new File(nativeDir, libName + libExt);
                        if (libFile.exists()) {
                            System.load(libFile.getAbsolutePath());
                            loaded = true;
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            loadError = e.getMessage();
        }

        /* 3. fallback: java.library.path */
        try {
            System.loadLibrary(libName);
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
        }
    }

    /**
     * Check whether the native library loaded successfully.
     * @return true if native methods are available
     */
    public static boolean isNativeLoaded() {
        return loaded;
    }

    /**
     * Get the error message from the last load attempt, or null if successful.
     */
    public static String getLoadError() {
        return loadError;
    }

    /* ================================================================
     *  Native method declarations — FileMana
     * ================================================================ */

    /** Read file lines. Returns empty array on error. */
    public static native String[] file_readLines(String filePath, String encoding);

    /** Write lines to file. Returns line count or -1. */
    public static native int file_writeLines(String filePath, String[] lines, boolean withNewline);

    /** Append lines to file. Returns line count or -1. */
    public static native int file_appendLines(String filePath, String[] lines, boolean withNewline);

    /** Delete a file. Returns 0 on success, -1 on error. */
    public static native int file_delete(String path);

    /** Copy file. Returns 0 on success, -1 on error. */
    public static native int file_copy(String src, String dst);

    /** Check file existence. Returns true if exists. */
    public static native boolean file_exists(String path);

    /* ================================================================
     *  Native method declarations — StrEdit
     * ================================================================ */

    /** Cast Object[] to String[] (toString each element). */
    public static native String[] str_castArr(Object[] objArr);

    /** Join String[] into single String, optionally with newlines. */
    public static native String str_join(String[] arr, boolean withNewline);

    /** Split string into array of single-character strings. */
    public static native String[] str_splitChars(String str);

    /** Remove matching lines or substrings. */
    public static native String[] str_remove(String[] src, String pattern, boolean lineMode);

    /** Replace all occurrences. */
    public static native String str_replace(String src, String oldStr, String newStr);

    /* ================================================================
     *  Native method declarations — MathCast
     * ================================================================ */

    /** Chinese number string → long (e.g. "一百二十三" → 123). */
    public static native long math_chineseToLong(String chinese);

    /** long → Chinese string (upperCase = true for 大写 壹贰叁). */
    public static native String math_longToChinese(long value, boolean upperCase);

    /** double → Chinese string. */
    public static native String math_doubleToChinese(double value, boolean upperCase);

    /** Chinese number string → double (e.g. "一百二十三点四五六" → 123.456). */
    public static native double math_chineseToDouble(String chinese);

    /** Numeric string → long (e.g. "123" → 123). */
    public static native long math_strToLong(String numeric);

    /* ================================================================
     *  Native method declarations — CMD
     * ================================================================ */

    /** Execute a system command and return output lines. */
    public static native String[] cmd_exec(String cmd, boolean isWindows);

    /* ================================================================
     *  Native method declarations — Randoms
     * ================================================================ */

    /** Random integer in [min, max] inclusive. */
    public static native int rand_intRange(int min, int max);

    /** Pick a random element from int array. */
    public static native int rand_intArray(int[] arr);

    /* ================================================================
     *  Native method declarations — Search
     * ================================================================ */

    /** Find all positions of needle in haystack. */
    public static native long[] search_findAll(String haystack, String needle);

    /** Wildcard matching with * and ?. */
    public static native boolean search_wildcardMatch(String str, String pattern);

    /** Bracket matching: returns "open,close" string pairs, empty if no match. */
    public static native String[] search_bracketMatch(String str);

    /* ================================================================
     *  Convenience facade methods (API similar to original SaComS)
     * ================================================================ */

    /** Read file lines, returns empty array on failure. */
    public static String[] readFileLines(String path) {
        return file_readLines(path, null);
    }

    /** Read file lines with encoding (e.g. "utf-8", "gbk"). */
    public static String[] readFileLines(String path, String encoding) {
        return file_readLines(path, encoding);
    }

    /** Write string array to file. */
    public static int writeFileLines(String path, String[] lines, boolean withNewline) {
        return file_writeLines(path, lines, withNewline);
    }

    /** Auto-detect OS and execute command. */
    public static String[] execCommand(String cmd) {
        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        return cmd_exec(cmd, isWin);
    }

    /** Execute on Windows explicitly. */
    public static String[] execCommandWin(String cmd) {
        return cmd_exec(cmd, true);
    }

    /** Execute on Linux/Mac explicitly. */
    public static String[] execCommandUnix(String cmd) {
        return cmd_exec(cmd, false);
    }

    /** Random int between min (inclusive) and max (inclusive). */
    public static int randomRange(int min, int max) {
        return rand_intRange(min, max);
    }

    /** Replace all occurrences, null-safe. */
    public static String replaceAll(String src, String oldStr, String newStr) {
        if (src == null) return null;
        if (oldStr == null || oldStr.isEmpty()) return src;
        return str_replace(src, oldStr, newStr);
    }
}
