package com.dropluckledger.ledger;

/**
 * Settings stored in the ledger file (same names as the web app so a backup moves between the two).
 */
public final class Prefs
{
	/**
	 * Number of upcoming kills the "next kills" figures look at.
	 */
	public int window = 50;
	/**
	 * Wearing a ring of wealth (i): 1 or 0.
	 */
	public int rowi;
	/**
	 * Legends' Quest completed: 1 or 0.
	 */
	public int lq = 1;
	public String theme = "";
	public String scope = "all";
	public String sel = "";
	public String tab = "tracker";
	public int images = 1;

	public Prefs copy()
	{
		Prefs p = new Prefs();
		p.window = window;
		p.rowi = rowi;
		p.lq = lq;
		p.theme = theme;
		p.scope = scope;
		p.sel = sel;
		p.tab = tab;
		p.images = images;
		return p;
	}
}
