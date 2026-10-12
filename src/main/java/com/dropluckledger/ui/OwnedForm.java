package com.dropluckledger.ui;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * "Items I already own": how many copies the player has of each unique, so that the luck figures count them.  The copies
 * are added without a kill number.
 */
@SuppressWarnings("serial")
final class OwnedForm extends Column
{
	private static final int WIDTH = 280;

	private final Ledger ledger;
	private final String bossId;
	private final Map<String, JSpinner> spinners = new LinkedHashMap<>();
	private final Map<String, Integer> have = new LinkedHashMap<>();

	OwnedForm(Ctx ctx, String bossId)
	{
		super(4);
		this.ledger = ctx.ledger();
		this.bossId = bossId;
		Boss boss = ledger.data().boss(bossId);
		BossState st = ledger.copyOf(bossId);
		add(Look.wrapped("Enter how many copies you have received so far. They are added without a kill number (use Edit in the drop history if you remember it)."
			+ " This only adds; delete a drop from the history to remove one.", WIDTH, Look.small(), Look.MUTED));
		List<String> names = new ArrayList<>();
		for (String n : ledger.trackedNames(bossId))
		{
			if (!Catalog.kindOf(boss, n).equals("other") || boss.logSet().contains(n))
			{
				names.add(n);
			}
		}
		for (String n : names)
		{
			int h = st.copies(n);
			have.put(n, h);
			JSpinner s = new JSpinner(new SpinnerNumberModel(h, 0, 999, 1));
			spinners.put(n, s);
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			JLabel label = new JLabel(n);
			label.setFont(Look.small());
			ItemIcon.set(label, ctx.icons(), n, ctx.pictures(), 24, 22);
			row.add(label, BorderLayout.CENTER);
			s.setPreferredSize(new java.awt.Dimension(64, s.getPreferredSize().height));
			row.add(s, BorderLayout.EAST);
			add(row);
		}
		setPreferredSize(new java.awt.Dimension(WIDTH, getPreferredSize().height));
	}

	/**
	 * Adds the copies the player entered.
	 *
	 * @return how many copies were added
	 */
	int save()
	{
		int added = 0;
		for (Map.Entry<String, JSpinner> e : spinners.entrySet())
		{
			int want = (Integer) e.getValue().getValue();
			int h = have.get(e.getKey());
			if (want > h)
			{
				added += ledger.addOwned(bossId, e.getKey(), want - h);
			}
		}
		return added;
	}

	Map<String, JSpinner> spinners()
	{
		return spinners;
	}
}
