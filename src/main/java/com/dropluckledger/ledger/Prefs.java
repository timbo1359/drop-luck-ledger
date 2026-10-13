package com.dropluckledger.ledger;

import java.util.ArrayList;
import java.util.List;

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
	/**
	 * Plugin only: the bosses the player unticked under "Bosses to show", which the overview leaves out.  Kept as the ones hidden
	 * rather than the ones shown, so a boss added to the plugin later shows up.
	 */
	public List<String> hiddenBosses = new ArrayList<>();

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
		p.hiddenBosses = new ArrayList<>(hiddenBosses);
		return p;
	}
}
