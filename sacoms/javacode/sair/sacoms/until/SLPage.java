package sair.sacoms.until;

import java.io.Serializable;

/**
 * °üº¬Object indexºÍSLPage up,down
 **/
public class SLPage implements Serializable {
	private static final long serialVersionUID = -4877993859912835637L;
	private Object index;
	private SLPage up, down;

	public SLPage() {
	}

	public SLPage(SLPage IndexList) {
		this.setUp(IndexList);
		IndexList.setDown(this);
	}

	public SLPage getDown() {
		return down;
	}

	public void setDown(SLPage down) {
		this.down = down;
	}

	@SuppressWarnings("unchecked")
	public <T> T getIndex() {
		if (index == null)
			return null;
		return (T) index;
	}

	public void setIndex(Object index) {
		this.index = index;
	}

	public SLPage getUp() {
		return up;
	}

	public void setUp(SLPage up) {
		this.up = up;
	}
}
