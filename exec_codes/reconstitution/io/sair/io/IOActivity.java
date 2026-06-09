package sair.io;

import sair.Main;
import sair.Pathes;
import sair.io.ui.ConsUI;
import sair.user.Activity;

public class IOActivity extends Activity {

	public static void main(String[] args) {
		IOActivity io = new IOActivity();
		String path = "\"E:\\Test\\COPY_TEST\\Test TEXT.txt\"";
		Main.toTest(io, "txt", path);

	}

	private ActivityAction aa = new ActivityAction();

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {
		case "copy":
			return aa.copy(args);
		case "move":
			return aa.move(args);
		case "del":
			return aa.del(args);
		case "net":
			return aa.net(args);
		case "txt":
			return aa.txt(args);
		}
		return false;
	}

	@Override
	public String[] help() {
		String name = this.getName();
		return new String[] { //
				Pathes.printSplit, //
				"input output V2.0", //
				"Creater:Sair", //
				Pathes.printSplit, //
				name + "/copy [path1] [path2] : 把path1复制到path2,这里path1是目标文件绝对路径", //
				"\t而path2指定的一定是一个文件夹,如果不存在,则会创建,意思就是", //
				"\t把path1的[目录/文件]复制到path2[目录内]", //
				name + "/move [path1] [path2] : 把path1移动到path2,这里是和copy命令同样的原理", //
				name + "/del [path] : 把path1 文件/文件夹 删除", //
				name + "/net [url] [path] : 从url下载到本地,因为下载的一定是一个文件而不是文件夹,", //
				"\t所以path指定的就是一个文件的目录,文件内容就是url指向文件的内容(URL也需要双引号)", //
				name + "/txt [path] [code] : 把path处文件以txt形式打开(控件内操作)", //
				"\t[code]可以留空,默认为UTF-8", //
				name + "/exit 这个命令只是清除掉HashMap中的控件缓存"

		};
	}

	@Override
	public void exit() {
		ConsUI.clear();
	}

}
