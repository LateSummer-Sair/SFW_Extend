package sair.sacoms.until;

import sair.sacoms.utfcode.SFS_UFTCODE;

public class SFS {

	public static final char splits = 'G', zero = '0', low = '-', lowCh = '负', pointCh = '点', point = '.';
	public static final char[] VM = { splits, '十', '百', '千', '万', '亿', '兆', '京', '垓', '秭', '穰', '沟', '涧', '正', '载', '极',
			'恒', '那', '不', '无' };
	public static final char[] BigChMath = new char[] { '零', '壹', '貳', '叁', '肆', '伍', '陸', '柒', '扒', '玖' };
	public static final char[] SmallChMath = new char[] { '零', '一', '二', '三', '四', '五', '六', '七', '八', '九' };

	@Deprecated
	public static final char[] miarry = SFS_UFTCODE.miarry;
}