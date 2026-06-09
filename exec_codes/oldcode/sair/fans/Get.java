package sair.fans;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLConnection;

class Get {

	private URLConnection con_0, con_1;

	Get(URL url_0, URL url_1) {
		try {
			link(url_0, url_1);
		} catch (IOException e) {
			//SaLogger.outLogger(e);
		}
	}

	private void link(URL url_0, URL url_1) throws IOException {
		con_0 = url_0.openConnection();
		con_1 = url_1.openConnection();
	}

	private String Name() throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(con_0.getInputStream()));
		StringBuffer buffer = new StringBuffer();
		String inputLine;
		while ((inputLine = br.readLine()) != null)
			buffer.append(inputLine);
		JsonElement element = JsonParser.parseString(buffer.toString());
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			if (object.get("code").getAsInt() == 0) {
				String s = object.getAsJsonObject("data").get("name").getAsString();
				if (s != null)
					s = new String(s.getBytes(), "UTF-8");
				return s;
			}
		}
		return "没有获取到ID";
	}

	private int Fans() throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(con_1.getInputStream()));
		StringBuffer buffer = new StringBuffer();
		String inputLine;
		while ((inputLine = br.readLine()) != null) {
			buffer.append(inputLine);
		}
		JsonElement element = JsonParser.parseString(buffer.toString());
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			if (object.get("code").getAsInt() == 0) {
				JsonObject data = object.getAsJsonObject("data");
				return data.get("follower").getAsInt();
			}
		}
		return 0;
	}

	/**
	 * 获得Bilier
	 * 
	 * @throws IOException
	 */
	Bilier getData() throws IOException {
		return new Bilier(Name(), Fans());
	}

	/**
	 * 作为工具方法使用
	 * 
	 * @throws IOException
	 */
	static String getNameOfNewestFans(int UploaderUID, int fans) throws IOException {
		StringBuffer fansName = new StringBuffer();
		int pn, ps;
		if (fans <= 50) {
			pn = 1;
			ps = fans;
		} else {
			pn = BigDecimal.valueOf(fans / 50).setScale(0, BigDecimal.ROUND_UP).intValue();
			ps = 50;
		}

		URL aURL = new URL(
				"http://api.bilibili.com/x/relation/followers?vmid=" + UploaderUID + "&ps=" + ps + "&pn=" + pn);
		URLConnection conn = aURL.openConnection();
		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		StringBuffer buffer = new StringBuffer();
		String inputLine;
		while ((inputLine = br.readLine()) != null) {
			buffer.append(inputLine);
		}

		JsonElement element = JsonParser.parseString(buffer.toString());
		JsonObject object = element.getAsJsonObject();
		if (object.get("code").getAsInt() == 0) {
			JsonObject data = object.get("data").getAsJsonObject();
			JsonArray list = data.get("list").getAsJsonArray();
			for (int i = 0; i < fans; i++) {
				JsonObject fan = list.get(i).getAsJsonObject();
				fansName.append(fan.get("uname").getAsString()).append('(').append(fan.get("mid").getAsString())
						.append(')');
				if (fans >= 2)
					if (i < fans - 1)
						fansName.append(',');
			}
		}
		return new String(fansName.toString().getBytes(), "UTF-8");
	}
}
