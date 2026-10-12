package com.dropluckledger.ui;

import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.ItemIcons;
import java.awt.Component;

/**
 * What the parts of the panel can ask of the panel that holds them.
 */
interface Ctx
{
	Ledger ledger();

	/**
	 * The luck maths.  Only for use inside a {@link Jobs} slot, never on the Swing thread.
	 */
	Analyzer analyzer();

	Jobs jobs();

	ItemIcons icons();

	HistoryPlanner planner();

	LedgerPanel.Host host();

	Dialogs dialogs();

	/**
	 * Whether item pictures are shown.
	 */
	boolean pictures();

	/**
	 * Something to parent windows to.
	 */
	Component window();

	void openBoss(String bossId);

	void openOverview();

	void openSettings();

	/**
	 * Shows a note at the top of the panel.
	 */
	void notice(String text);

	/**
	 * Remembers which boss, tab and scope the player last used (saved with the ledger).  Does not make anything refresh.
	 */
	void remember(String boss, String tab, String scope);

	/**
	 * Looks for what the game knows about the player's history and offers it for review.
	 */
	void reviewHistory();
}
