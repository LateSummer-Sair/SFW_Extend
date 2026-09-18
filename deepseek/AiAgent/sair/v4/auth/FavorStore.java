package sair.v4.auth;

import com.google.gson.JsonObject;

import java.util.List;

/** 好感度持久化端口（由 store 层实现，基板其余部分只依赖这个窄接口）。 */
public interface FavorStore {

    double favor(long qq);

    void setFavor(long qq, double value, String level, String note);

    List<JsonObject> top(int limit);

    int clear();

    /** 按人建账（首次打交道建一行，已有则不动）。 */
    void ensure(long qq);

    /** 记一笔流水（谁、加减多少、改完多少、什么 op、谁改的、为什么）。 */
    void log(long qq, double delta, double after, String op, String by, String why);

    /** 某人的好感度流水（新 → 旧）。 */
    List<JsonObject> events(long qq, int limit);
}
