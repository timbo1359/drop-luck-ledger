package com.dropluckledger.ui;

import com.dropluckledger.history.HistoryPlanner;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Shows what the game's records would add to the ledger, with a tick for each line, before anything is added.
 */
@SuppressWarnings("serial")
final class HistoryForm extends JPanel
{
	private final HistoryPlanner.Plan plan;
	private final JLabel summary = new JLabel();
	private final KcModel kcModel;
	private final ItemModel itemModel;
	private final JTable kcTable;
	private final JTable itemTable;

	HistoryForm(HistoryPlanner.Plan plan)
	{
		super(new BorderLayout(0, 6));
		this.plan = plan;
		setPreferredSize(new Dimension(560, 440));

		JPanel top = new JPanel();
		top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
		JLabel from = new JLabel(Look.html(520, "Read from: " + Look.esc(String.join(", ", plan.sources))
			+ ".<br>The game does not record which kill each old drop came on, so items come in without a kill number. They count towards "
			+ "your luck right away (the kills you have made against the copies you own); no dry streak is shown for an item until you edit that drop "
			+ "and add its kill number. Items that drop in stacks (bolt racks, frozen tears) are turned into an estimated number of drops, marked ~."));
		from.setFont(Look.small());
		top.add(from);
		add(top, BorderLayout.NORTH);

		kcModel = new KcModel(plan.kc);
		kcTable = table(kcModel, new int[] {24, 190, 80, 80, 140});
		itemModel = new ItemModel(plan.items);
		itemTable = table(itemModel, new int[] {24, 140, 90, 130, 55, 45});

		JPanel center = new JPanel();
		center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
		center.add(heading("Kill counts", kcModel, plan.kc.size()));
		center.add(new JScrollPane(kcTable));
		center.add(javax.swing.Box.createVerticalStrut(8));
		center.add(heading("Items you own", itemModel, plan.items.size()));
		center.add(new JScrollPane(itemTable));
		add(center, BorderLayout.CENTER);

		JPanel bottom = new JPanel();
		bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
		summary.setFont(Look.body());
		bottom.add(summary);
		String left = leftOut(plan);
		if (!left.isEmpty())
		{
			JLabel l = new JLabel(Look.html(520, left));
			l.setFont(Look.small());
			bottom.add(l);
		}
		add(bottom, BorderLayout.SOUTH);
		refreshSummary();
	}

	private static String leftOut(HistoryPlanner.Plan p)
	{
		StringBuilder b = new StringBuilder();
		if (!p.kcSkipped.isEmpty())
		{
			b.append("Not used: ").append(Look.esc(String.join(" · ", p.kcSkipped.subList(0, Math.min(25, p.kcSkipped.size())))));
			if (p.kcSkipped.size() > 25)
			{
				b.append(" …");
			}
			b.append(" (other activities, other modes of a boss, or bosses not in the ledger).<br>");
		}
		if (!p.itemsMissing.isEmpty())
		{
			b.append("Collection log items that are not tracked here: ").append(Look.esc(String.join(" · ", p.itemsMissing.subList(0, Math.min(25, p.itemsMissing.size())))));
			if (p.itemsMissing.size() > 25)
			{
				b.append(" …");
			}
		}
		return b.toString();
	}

	private JTable table(AbstractTableModel m, int[] widths)
	{
		JTable t = new JTable(m);
		t.setFillsViewportHeight(true);
		t.setRowHeight(t.getRowHeight() + 2);
		t.setPreferredScrollableViewportSize(new Dimension(520, 120));
		for (int i = 0; i < widths.length; i++)
		{
			t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		t.getColumnModel().getColumn(0).setMaxWidth(30);
		m.addTableModelListener(e -> refreshSummary());
		return t;
	}

	private JPanel heading(String title, AbstractTableModel model, int rows)
	{
		JPanel p = new JPanel(new BorderLayout());
		JLabel l = new JLabel(title + " (" + rows + ")");
		l.setFont(Look.bold());
		p.add(l, BorderLayout.WEST);
		JCheckBox all = new JCheckBox("Tick all");
		all.setFont(Look.small());
		all.setSelected(true);
		all.addActionListener(e ->
		{
			if (model instanceof KcModel)
			{
				((KcModel) model).setAll(all.isSelected());
			}
			else
			{
				((ItemModel) model).setAll(all.isSelected());
			}
		});
		p.add(all, BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	private void refreshSummary()
	{
		int k = plan.kcToApply();
		int rows = plan.itemRowsToApply();
		int c = plan.copiesToAdd();
		int back = plan.copiesToRemove();
		List<String> parts = new ArrayList<>();
		if (k > 0)
		{
			parts.add(k + " kill count" + (k == 1 ? "" : "s"));
		}
		if (c > 0)
		{
			parts.add(c + " owned item" + (c == 1 ? "" : "s") + " (" + rows + " line" + (rows == 1 ? "" : "s") + ")");
		}
		String text = parts.isEmpty() ? "" : "Will add: " + String.join(" and ", parts);
		if (back > 0)
		{
			text += (text.isEmpty() ? "" : ". ") + "Will take back " + back + " item" + (back == 1 ? "" : "s") + " an earlier import counted too many times";
		}
		summary.setText(text.isEmpty() ? "Nothing is ticked." : text);
	}

	HistoryPlanner.Plan plan()
	{
		return plan;
	}

	JLabel summaryLabel()
	{
		return summary;
	}

	/**
	 * Sets the tick of a kill count line (for tests).
	 */
	void tickKc(int row, boolean on)
	{
		kcModel.setValueAt(on, row, 0);
	}

	void tickItem(int row, boolean on)
	{
		itemModel.setValueAt(on, row, 0);
	}

	// ------------------------------------------------------------------ the two lists

	@SuppressWarnings("serial")
	private static final class KcModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {"", "Boss", "In game", "In ledger", "Result"};
		private final List<HistoryPlanner.KcRow> rows;

		KcModel(List<HistoryPlanner.KcRow> rows)
		{
			this.rows = rows;
		}

		void setAll(boolean on)
		{
			for (HistoryPlanner.KcRow r : rows)
			{
				r.on = on && r.to > r.cur;
			}
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLUMNS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int r, int c)
		{
			return c == 0 && rows.get(r).to > rows.get(r).cur;
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			HistoryPlanner.KcRow row = rows.get(r);
			switch (c)
			{
				case 0:
					return row.on;
				case 1:
					return row.name;
				case 2:
					return Fmt.num(row.to);
				case 3:
					return Fmt.num(row.cur);
				default:
					return row.to > row.cur ? "sets the kill count" : row.to == row.cur ? "already the same" : "your ledger is ahead, kept";
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c)
		{
			if (c == 0)
			{
				rows.get(r).on = (Boolean) v;
				fireTableCellUpdated(r, c);
			}
		}
	}

	@SuppressWarnings("serial")
	private static final class ItemModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {"", "Item", "Boss", "Owned", "In ledger", "Adds"};
		private final List<HistoryPlanner.ItemRow> rows;

		ItemModel(List<HistoryPlanner.ItemRow> rows)
		{
			this.rows = rows;
		}

		void setAll(boolean on)
		{
			for (HistoryPlanner.ItemRow r : rows)
			{
				r.on = on && (r.add > 0 || r.remove > 0);
			}
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int c)
		{
			return COLUMNS[c];
		}

		@Override
		public Class<?> getColumnClass(int c)
		{
			return c == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int r, int c)
		{
			return c == 0 && (rows.get(r).add > 0 || rows.get(r).remove > 0);
		}

		@Override
		public Object getValueAt(int r, int c)
		{
			HistoryPlanner.ItemRow row = rows.get(r);
			switch (c)
			{
				case 0:
					return row.on;
				case 1:
					return row.item;
				case 2:
					return row.bossName + (row.assumed ? " (assumed)" : "");
				case 3:
					return row.estimated() ? "~" + Fmt.num(row.want) + " (" + Fmt.num(row.quantity) + " items)" : Fmt.num(row.want);
				case 4:
					return Fmt.num(row.have);
				default:
					return row.add > 0 ? "+" + Fmt.num(row.add) : row.remove > 0 ? "-" + Fmt.num(row.remove) : "—";
			}
		}

		@Override
		public void setValueAt(Object v, int r, int c)
		{
			if (c == 0)
			{
				rows.get(r).on = (Boolean) v;
				fireTableCellUpdated(r, c);
			}
		}
	}
}
