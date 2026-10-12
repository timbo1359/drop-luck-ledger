package com.dropluckledger.ui;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Engine.ItemMeta;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.awt.BorderLayout;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The form for logging a drop, or changing one that was logged.
 */
@SuppressWarnings("serial")
final class LogForm extends Column
{
	private static final int WIDTH = 280;

	private final Ledger ledger;
	private final Analyzer lookup;
	private final String bossId;
	private final Drop edit;
	private final JComboBox<String> item;
	private final JSpinner kill;
	private final JCheckBox unknown = new JCheckBox("I don't know which kill it was");
	private final JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, 1_000_000_000, 1));
	private final Column pooling = new Column(4);
	private final Map<String, JSpinner> snaps = new LinkedHashMap<>();
	private final Map<String, Integer> remembered = new LinkedHashMap<>();

	/**
	 * @param edit      the drop to change, or null to log a new one
	 * @param preselect the item to start with when logging a new drop
	 */
	LogForm(Ledger ledger, Analyzer lookup, String bossId, Drop edit, String preselect)
	{
		super(6);
		this.ledger = ledger;
		this.lookup = lookup;
		this.bossId = bossId;
		this.edit = edit;
		Boss boss = ledger.data().boss(bossId);
		BossState st = ledger.copyOf(bossId);

		List<String> tracked = ledger.trackedNames(bossId);
		List<String> others = new ArrayList<>();
		for (Map.Entry<String, ItemMeta> e : boss.meta().entrySet())
		{
			if (!tracked.contains(e.getKey()))
			{
				others.add(e.getKey());
			}
		}
		others.sort(Collator.getInstance(Locale.ENGLISH));
		List<String> all = new ArrayList<>(tracked);
		all.addAll(others);
		item = new JComboBox<>(all.toArray(new String[0]));
		item.setFont(Look.small());
		String start = edit != null ? edit.item : preselect;
		item.setSelectedItem(start != null && all.contains(start) ? start : null);

		int kc = edit != null ? (edit.kc == null ? 0 : edit.kc) : st.kc;
		kill = new JSpinner(new SpinnerNumberModel(Math.max(1, kc), 1, 1_000_000_000, 1));
		if (edit != null)
		{
			qty.setValue(Math.max(1, edit.qty));
			if (edit.snap != null)
			{
				remembered.putAll(edit.snap);
			}
		}
		boolean unk = edit != null && edit.kc == null;
		unknown.setSelected(unk);
		unknown.setFont(Look.small());
		unknown.setOpaque(false);
		kill.setEnabled(!unk);
		unknown.addActionListener(e -> kill.setEnabled(!unknown.isSelected()));

		add(captioned("Item", item));
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.add(captioned("Kill number it dropped on", kill), BorderLayout.CENTER);
		JPanel q = captioned("Quantity", qty);
		q.setPreferredSize(new java.awt.Dimension(80, q.getPreferredSize().height));
		row.add(q, BorderLayout.EAST);
		add(row);
		add(unknown);
		add(pooling);
		item.addActionListener(e -> rebuildPooling());
		rebuildPooling();
		setPreferredSize(new java.awt.Dimension(WIDTH, getPreferredSize().height));
	}

	private static JPanel captioned(String caption, javax.swing.JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setOpaque(false);
		p.add(Look.muted(caption), BorderLayout.NORTH);
		p.add(c, BorderLayout.CENTER);
		return p;
	}

	/**
	 * For an item several bosses drop, asks for the other bosses' kill counts at the time (to pool the luck).
	 */
	private void rebuildPooling()
	{
		for (Map.Entry<String, JSpinner> e : snaps.entrySet())
		{
			remembered.put(e.getKey(), (Integer) e.getValue().getValue());
		}
		snaps.clear();
		pooling.removeAll();
		String name = (String) item.getSelectedItem();
		if (name != null && lookup.shared(name))
		{
			List<String> others = new ArrayList<>();
			for (String id : lookup.bossesWith(name))
			{
				if (!id.equals(bossId) && ledger.kc(id) > 0)
				{
					others.add(id);
				}
			}
			if (!others.isEmpty())
			{
				pooling.add(Look.wrapped("<b>Other bosses that drop this</b><br>Used to pool your luck across bosses. Enter each boss's kill count at the time"
					+ " (this is filled in with your current counts).", WIDTH, Look.small(), Look.TEXT));
				for (String id : others)
				{
					int v = remembered.containsKey(id) ? remembered.get(id) : ledger.kc(id);
					JSpinner s = new JSpinner(new SpinnerNumberModel(v, 0, 1_000_000_000, 1));
					snaps.put(id, s);
					pooling.add(captioned(ledger.data().boss(id).name, s));
				}
			}
		}
		pooling.revalidate();
		pooling.repaint();
		revalidate();
		java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
		if (w != null)
		{
			w.pack();
		}
	}

	/**
	 * What is wrong with the form, or null.
	 */
	String problem()
	{
		if (item.getSelectedItem() == null)
		{
			return "Choose an item.";
		}
		if (!unknown.isSelected() && (Integer) kill.getValue() < 1)
		{
			return "Enter the kill number, or tick \"I don't know which kill it was\".";
		}
		return null;
	}

	/**
	 * Writes the drop to the ledger.
	 *
	 * @return a short description of what was logged
	 */
	String save()
	{
		String name = (String) item.getSelectedItem();
		Integer kc = unknown.isSelected() ? null : (Integer) kill.getValue();
		int quantity = (Integer) qty.getValue();
		Map<String, Integer> snap = null;
		if (!snaps.isEmpty())
		{
			snap = new LinkedHashMap<>();
			for (Map.Entry<String, JSpinner> e : snaps.entrySet())
			{
				snap.put(e.getKey(), (Integer) e.getValue().getValue());
			}
		}
		if (edit != null)
		{
			ledger.editDrop(bossId, edit.id, name, kc, quantity, snap);
		}
		else
		{
			ledger.logDrop(bossId, name, kc, quantity, snap, Drop.SRC_MANUAL);
		}
		return name + (kc != null ? " at kill " + Fmt.num(kc) : "");
	}

	// ---- for tests

	JComboBox<String> itemBox()
	{
		return item;
	}

	JSpinner killSpinner()
	{
		return kill;
	}

	JCheckBox unknownBox()
	{
		return unknown;
	}

	JSpinner quantitySpinner()
	{
		return qty;
	}

	Map<String, JSpinner> snapshotSpinners()
	{
		return snaps;
	}
}
