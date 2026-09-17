package sair.v4.kit;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** 纯文本输出（探针/无 GUI 环境），可选捕获留痕。 */
public final class PlainOut implements Out {

    private final PrintStream ps;
    private final boolean echo;
    private final List<String> captured = new ArrayList<String>();

    public PlainOut(PrintStream ps, boolean echo) {
        this.ps = ps == null ? System.out : ps;
        this.echo = echo;
    }

    public static PlainOut quiet() { return new PlainOut(System.out, false); }

    public static PlainOut echo() { return new PlainOut(System.out, true); }

    @Override
    public synchronized void print(String text, Tone tone) {
        String s = text == null ? "" : text;
        captured.add(s);
        if (echo) ps.print(s);
    }

    /** 已输出全文（探针断言用）。 */
    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : captured) sb.append(s);
        return sb.toString();
    }

    public synchronized void clear() { captured.clear(); }
}
