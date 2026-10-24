package com.dropluckledger.ui;

import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.TrackingState;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stands in for the plugin.
 */
final class FakeHost implements LedgerPanel.Host
{
	volatile TrackingState state = TrackingState.TRACKING;
	HistoryPlanner.Plan plan = new HistoryPlanner.Plan();
	Ledger backupToImport;
	int planRequests;
	int exports;
	int imports;
	final List<Ledger> replaced = new ArrayList<>();
	final List<String> backfilled = new ArrayList<>();
	int dropLogsErased;
	/**
	 * The lists from the game's records the panel asked to add, and what the plugin answers.
	 */
	final List<HistoryPlanner.Plan> synced = new ArrayList<>();
	int[] syncAnswer = {0, 0, 0, 0};
	int syncDismissals;

	@Override
	public TrackingState state()
	{
		return state;
	}

	@Override
	public void planHistory(Consumer<HistoryPlanner.Plan> then)
	{
		planRequests++;
		then.accept(plan);
	}

	@Override
	public int[] applySync(HistoryPlanner.Plan plan)
	{
		synced.add(plan);
		return syncAnswer;
	}

	@Override
	public void dismissSync()
	{
		syncDismissals++;
	}

	@Override
	public void exportBackup(Component parent)
	{
		exports++;
	}

	@Override
	public void importBackup(Component parent, Consumer<Ledger> confirm)
	{
		imports++;
		if (backupToImport != null)
		{
			confirm.accept(backupToImport);
		}
	}

	@Override
	public void replaceLedger(Ledger backup)
	{
		replaced.add(backup);
	}

	@Override
	public void backfill(String bossId, String item)
	{
		backfilled.add(bossId + ":" + item);
	}

	@Override
	public void eraseDropLog()
	{
		dropLogsErased++;
	}
}
