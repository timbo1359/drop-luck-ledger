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
}
