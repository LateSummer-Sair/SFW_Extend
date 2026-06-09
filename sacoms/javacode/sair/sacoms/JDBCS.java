package sair.sacoms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import sair.sacoms.until.Password;

/**
 *
 * <p>
 * JDBC_IO数据库操作工具类<br>
 * 
 * @author _Sair
 * @version JDBCS_1.0
 * 
 **/
public class JDBCS {
	/**
	 * 本工具包内默认集成的SQLSERVER_2008驱动钥匙
	 */
	public final static Key<String> SQLSERVER_2008 = Key.creatKey("com.microsoft.sqlserver.jdbc.SQLServerDriver");

	/**
	 * 新建一个驱动钥匙
	 * 
	 * @param drive
	 *            驱动类的绝对包名地址
	 * @return Key<String>类型
	 */
	public static Key<String> creatNewJDBCkey(String drive) {
		return Key.creatKey(drive);
	}

	private Statement st;
	private Connection con;
	private String driverKeyStr, urlStr, userStr;
	private char[] passChar;

	/**
	 * 类初始构造器
	 */
	public JDBCS() {
	}

	/**
	 * 类初始构造器
	 * 
	 * @param driverKey
	 *            JDBC三方驱动来自于本类提供或者新建地址。
	 * @param url
	 *            数据库连接地址
	 * @param user
	 *            登录用户名
	 * @param pass
	 *            登录用户密码,可由Password.castoPassword()提供创建。
	 * @param passKey
	 *            Password.castoPassword()创建密码类型时使用的Key。
	 * @throws KeyIsNotTrue
	 * @throws SQLException
	 */
	public JDBCS(Key<String> driverKey, String url, String user, Password pass, Key<Long> passKey) throws SQLException {
		reSetAllProperties(driverKey, url, user, pass, passKey);
	}

	/**
	 * 重置整个工具连接器，此操作会导致原数据库连接断开
	 * <p>
	 * <br>
	 * 连接实例：<br>
	 * Key<Long> KEY = Key.creatKey(1314520L);<br>
	 * JDBCS _jdbc=new JDBCS();<br>
	 * _jdbc.reSetAllProperties(JDBCk.SQLSERVER_2008,<br>
	 * "jdbc:sqlserver://localhost:1433;DatabaseName=SairsDataBase",<br>
	 * "sa",<br>
	 * Password.castoPassword("gjy1284688456", KEY), <br>
	 * KEY);<br>
	 * 
	 * @param driverKey
	 *            JDBC三方驱动来自于com.sair.key.JDBCk提供或者新建地址。
	 * @param url
	 *            数据库连接地址
	 * @param user
	 *            登录用户名
	 * @param pass
	 *            登录用户密码,可由Password.castoPassword()提供创建。
	 * @param passKey
	 *            Password.castoPassword()创建密码类型时使用的Key。
	 * @throws KeyIsNotTrue
	 * @throws SQLException
	 */
	public void reSetAllProperties(Key<String> driverKey, String url, String user, Password pass, Key<Long> passKey)
			throws SQLException {
		closeAll();
		urlStr = url;
		setImport(driverKey, user, pass, passKey);
	}

	private void setImport(Key<String> driverKey, String user, Password pass, Key<Long> passKey) {
		driverKeyStr = driverKey.about();
		userStr = user;
		passChar = pass.getPassWordOf(passKey);
	}

	/**
	 * 获取 Connection
	 * 
	 * @return Connection类型
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public Connection getConnection() throws ClassNotFoundException, SQLException {
		if (con == null) {
			Class.forName(driverKeyStr);
			con = DriverManager.getConnection(urlStr, userStr, new String(passChar));
		}
		return con;
	}

	/**
	 * 获取 Statement
	 * 
	 * @return Statement类型
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public Statement getStatement() throws ClassNotFoundException, SQLException {
		if (st == null) {
			Connection cons = getConnection();
			cons.setAutoCommit(true);
			st = cons.createStatement();
		}
		return st;
	}

	/**
	 * 获取ResultSet命令反馈
	 * 
	 * @param SqlStr
	 *            数据库操作语句
	 * @return ResultSet类型
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public ResultSet getResultSet(String SqlStr) throws ClassNotFoundException, SQLException {
		return getStatement().executeQuery(SqlStr);
	}

	/**
	 * 直接获取整个列
	 * 
	 * @param SqlStr
	 *            数据库操作语句
	 * @param RowHeadName
	 *            列头/列名称
	 * @return SairLists<String> 类型
	 * @throws SQLException
	 * @throws ClassNotFoundException
	 */
	public SairLists<String> toRowData(String SqlStr, String RowHeadName) throws SQLException, ClassNotFoundException {
		SairLists<String> listBack = new SairLists<String>();
		String index;
		ResultSet rs = getResultSet(SqlStr);
		while (rs.next()) {
			index = rs.getString(RowHeadName);
			listBack.add(index);
		}
		return listBack;
	}

	/**
	 * 关闭Statement连接
	 * 
	 * @throws SQLException
	 */
	public void closeStatement() throws SQLException {
		if (st != null)
			st.close();
	}

	/**
	 * 关闭Connection连接
	 * 
	 * @throws SQLException
	 */
	public void closeConnection() throws SQLException {
		if (con != null)
			con.close();
	}

	/**
	 * 关闭Connection与Statement连接
	 * 
	 * @throws SQLException
	 */
	public void closeAll() throws SQLException {
		closeStatement();
		closeConnection();
	}
}
