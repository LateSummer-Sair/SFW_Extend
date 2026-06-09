package sair.sacoms;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 *
 * <p>
 * FTP_IO文件操作<br>
 * 
 * @author _代码来自沙雕网友(jar包来自阿法其)
 * @version FTP_1.0
 * 
 **/
public class FTP {

	/**
	 * 初始化FTP
	 * 
	 * @param hostname
	 *            FTP地址
	 * @param port
	 *            FTP端口（留空为默认端口）
	 * @param username
	 *            用户名（留空为匿名）
	 * @param password
	 *            用户密码（留空为匿名密码）
	 * @param systemCode
	 *            编码（留空为GBK）
	 */
	public FTP(String hostname, Integer port, String username, String password, String systemCode) {
		this.hostname = hostname;
		if (port != null)
			this.port = port;
		if (username != null)
			this.username = username;
		if (password != null)
			this.password = password;
		if (systemCode != null)
			this.systemCode = systemCode;
	}

	// FTP服务器地址
	private String hostname;
	// FTP服务器端口号默认为21
	private Integer port = 21;
	// FTP登录账号
	private String username = "anonymous";
	// FTP登录密码
	private String password = "1284688456@qq.com";

	private String systemCode = "GBK";

	private FTPClient ftpClient;

	/**
	 * 初始化FTP服务器
	 */
	private boolean initFtpClient() {
		boolean flag = false;
		ftpClient = new FTPClient();
		ftpClient.setControlEncoding(systemCode);
		try {
			ftpClient.connect(hostname, port); // 连接FTP服务器
			ftpClient.login(username, password); // 登录FTP服务器
			int replyCode = ftpClient.getReplyCode(); // 是否成功登录服务器
			if (FTPReply.isPositiveCompletion(replyCode))
				flag = true;
		} catch (MalformedURLException e) {
			// SaLogger.outLogger(e);
		} catch (IOException e) {
			// SaLogger.outLogger(e);
		}
		return flag;
	}

	/**
	 * 上传文件
	 * 
	 * @param pathname
	 *            FTP服务保存地址
	 * @param fileName
	 *            上传到FTP的文件名
	 * @param originfilename
	 *            待上传文件的名称（绝对地址） *
	 * @return boolean
	 */
	public boolean uploadFile(String pathname, String fileName, String originfilename) {
		boolean flag = false;
		InputStream inputStream = null;
		try {
			inputStream = new FileInputStream(new File(originfilename));
			initFtpClient();
			ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);
			CreateDirecroty(pathname);
			ftpClient.makeDirectory(pathname);
			ftpClient.changeWorkingDirectory(pathname);
			ftpClient.storeFile(fileName, inputStream);
			inputStream.close();
			ftpClient.logout();
			flag = true;
		} catch (Exception e) {
			// SaLogger.outLogger(e);
		} finally {
			if (ftpClient.isConnected()) {
				try {
					ftpClient.disconnect();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
			if (null != inputStream) {
				try {
					inputStream.close();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
		}
		return flag;
	}

	/**
	 * 上传文件
	 * 
	 * @param pathname
	 *            FTP服务保存地址
	 * @param fileName
	 *            上传到FTP的文件名
	 * @param inputStream
	 *            输入文件流
	 * @return Boolean
	 */
	public boolean uploadFile(String pathname, String fileName, InputStream inputStream) {
		boolean flag = false;
		try {
			initFtpClient();
			ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);
			CreateDirecroty(pathname);
			ftpClient.makeDirectory(pathname);
			ftpClient.changeWorkingDirectory(pathname);
			ftpClient.storeFile(fileName, inputStream);
			inputStream.close();
			ftpClient.logout();
			flag = true;
		} catch (Exception e) {
			// SaLogger.outLogger(e);
		} finally {
			if (ftpClient.isConnected()) {
				try {
					ftpClient.disconnect();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
			if (null != inputStream) {
				try {
					inputStream.close();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
		}
		return flag;
	}

	/**
	 * 改变FTP中的文件夹指向
	 * 
	 * @param directory
	 *            文件夹路径
	 * @return Boolean
	 */
	public boolean changeWorkingDirectory(String directory) {
		boolean flag = true;
		try {
			flag = ftpClient.changeWorkingDirectory(directory);
		} catch (IOException ioe) {
			// SaLogger.outLogger(ioe);
		}
		return flag;
	}

	/**
	 * 创建多级目录
	 * 
	 * @param remote
	 *            路径
	 * @return Boolean
	 */
	public boolean CreateDirecroty(String remote) throws IOException {
		boolean success = true;
		String directory = remote + "/";
		// 如果远程目录不存在，则递归创建远程服务器目录
		if (!directory.equalsIgnoreCase("/") && !changeWorkingDirectory(new String(directory))) {
			int start = 0;
			int end = 0;
			if (directory.startsWith("/"))
				start = 1;
			else
				start = 0;
			end = directory.indexOf("/", start);
			String path = "", paths = "";
			while (true) {
				String subDirectory = new String(remote.substring(start, end).getBytes(systemCode), "iso-8859-1");
				path = path + "/" + subDirectory;
				if (!existFile(path)) {
					if (makeDirectory(subDirectory)) {
						changeWorkingDirectory(subDirectory);
					} else {
						// System.out.println("creat dir [" + subDirectory + "]
						// fail");
						changeWorkingDirectory(subDirectory);
					}
				} else {
					changeWorkingDirectory(subDirectory);
				}

				paths = paths + "/" + subDirectory;
				start = end + 1;
				end = directory.indexOf("/", start);
				// 检查所有目录是否创建完毕
				if (end <= start)
					break;
			}
		}
		return success;
	}

	/**
	 * 判断此路径是否存在
	 * 
	 * @param path
	 *            路径
	 * @return Boolean
	 */
	public boolean existFile(String path) throws IOException {
		boolean flag = false;
		FTPFile[] ftpFileArr = ftpClient.listFiles(path);
		if (ftpFileArr.length > 0)
			flag = true;
		return flag;
	}

	/**
	 * 创建单级目录
	 * 
	 * @param dir
	 *            文件夹
	 * @return Boolean
	 */
	public boolean makeDirectory(String dir) {
		boolean flag = true;
		try {
			flag = ftpClient.makeDirectory(dir);
		} catch (Exception e) {
			// SaLogger.outLogger(e);
		}
		return flag;
	}

	/**
	 * * 下载文件 *
	 * 
	 * @param pathname
	 *            FTP服务器文件目录 *
	 * @param filename
	 *            文件名称 *
	 * @param localpath
	 *            下载后的文件路径 *
	 * @return Boolean
	 */
	public boolean downloadFile(String pathname, String filename, String localpath) {
		boolean flag = false;
		OutputStream os = null;
		try {
			initFtpClient();
			// 切换FTP目录
			ftpClient.changeWorkingDirectory(pathname);
			FTPFile[] ftpFiles = ftpClient.listFiles();
			for (FTPFile file : ftpFiles) {
				if (filename.equalsIgnoreCase(file.getName())) {
					File localFile = new File(localpath + "/" + file.getName());
					os = new BufferedOutputStream(new FileOutputStream(localFile), 8388608);
					ftpClient.retrieveFile(file.getName(), os);
					os.close();
				}
			}
			ftpClient.logout();
			flag = true;
		} catch (Exception e) {
			// SaLogger.outLogger(e);
		} finally {
			if (ftpClient.isConnected()) {
				try {
					ftpClient.disconnect();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
			if (null != os) {
				try {
					os.close();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
		}
		return flag;
	}

	/**
	 * * 删除文件 *
	 * 
	 * @param pathname
	 *            FTP服务器保存目录 *
	 * @param filename
	 *            要删除的文件名称 *
	 * @return Boolean
	 */
	public boolean deleteFile(String pathname, String filename) {
		boolean flag = false;
		try {
			initFtpClient();
			// 切换FTP目录
			ftpClient.changeWorkingDirectory(pathname);
			ftpClient.dele(filename);
			ftpClient.logout();
			flag = true;
		} catch (Exception e) {
			// SaLogger.outLogger(e);
		} finally {
			if (ftpClient.isConnected()) {
				try {
					ftpClient.disconnect();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			}
		}
		return flag;
	}

}
