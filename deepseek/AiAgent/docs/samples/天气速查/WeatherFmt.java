/**
 * 演示技能「天气速查」的辅助类。
 * <p>同一个技能文件夹里的 .java 会<b>一起编译</b>，所以辅助类可以随便拆；
 * 顶层类、静态内部类都能用（编译产物只存在于内存，不落盘）。</p>
 */
class WeatherFmt {

    /** 拼一句人话摘要。 */
    static String summarize(String city, long days, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(city).append(' ');
        if (days <= 1) {
            sb.append("今天");
        } else {
            sb.append("未来 ").append(days).append(" 天");
        }
        sb.append("：晴，18~26℃");
        if (!"简要".equals(mode)) {
            sb.append("；湿度 45%，东南风 2 级（").append(mode).append("模式）");
        }
        return sb.toString();
    }
}
