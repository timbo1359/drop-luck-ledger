package com.dropluckledger.ui;

import com.dropluckledger.TestData;
import com.dropluckledger.Wait;
import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.engine.Data;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.FakeGame;
import com.dropluckledger.plugin.ItemIcons;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A panel on a real ledger, with the windows and the plugin faked.
 */
final class PanelFixture
{
	final Data data = TestData.get();
	final Ledger ledger = new Ledger(data, () -> 1_700_000_000_000L);
	final BossIndex index = new BossIndex(data);
	final HistoryPlanner planner = new HistoryPlanner(ledger, index);
	final FakeGame game = new FakeGame();
	final ItemIcons icons = new ItemIcons(game);
	final FakeHost host = new FakeHost();
	final FakeDialogs dialogs = new FakeDialogs();
	final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "test-analysis");
		t.setDaemon(true);
		return t;
	});
	final LedgerPanel panel;

	PanelFixture()
	{
		this(true);
	}

	PanelFixture(boolean attach)
	{
		panel = Swings.get(() -> new LedgerPanel(host, executor, dialogs));
		if (attach)
		{
			attach();
		}
	}

	void attach()
	{
		panel.attach(ledger, index, icons, planner);
		panel.stateChanged();
		settle();
	}

	/**
	 * Waits until the panel has nothing left to work out or show.
	 */
	void settle()
	{
		final int[] quiet = {0};
		Wait.until("the panel to settle", 30, () ->
		{
			Swings.edt(() ->
			{
			});
			quiet[0] = panel.idle() ? quiet[0] + 1 : 0;
			return quiet[0] >= 3;
		});
	}

	void openBoss(String id)
	{
		Swings.edt(() -> panel.context().openBoss(id));
		settle();
	}

	void openOverview()
	{
		Swings.edt(() -> panel.context().openOverview());
		settle();
	}

	void openSettings()
	{
		Swings.edt(() -> panel.context().openSettings());
		settle();
	}

	String view()
	{
		return Swings.get(panel::viewName);
	}

	String pageText()
	{
		return Swings.get(() -> Swings.text(panel.bodyColumn()));
	}

	void close()
	{
		Swings.edt(panel::detach);
		executor.shutdownNow();
	}
}
