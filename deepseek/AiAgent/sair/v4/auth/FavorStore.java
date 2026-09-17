package sair.v4.auth;

import com.google.gson.JsonObject;

import java.util.List;

/** 好感度持久化端口（由 store 层实现，基板其余部分只依赖这个窄接口）。 */
public interface FavorStore {

    double favor(long qq);

    void setFavor(long qq, double value, String level, String note);

    List<JsonObject> top(int limit);

    int clear();
}
