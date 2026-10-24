package com.dropluckledger.ui;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.ItemIcons;
import com.dropluckledger.plugin.TrackingState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's side panel: an overview of the player's drop luck, one page for each boss, settings and backups.
 * <p>
 * Everything here is used on the Swing thread.  The luck maths runs on the executor given to the constructor.
 */
@SuppressWarnings("serial")
public final class LedgerPanel extends PluginPanel
{
	/**
	 * What the panel needs from the plugin.
	 */
	public interface Host
	{
		TrackingState state();

		/**
		 * Gathers what the game and RuneLite know about the player's history; the callback runs on the Swing thread.
		 */
		void planHistory(Consumer<HistoryPlanner.Plan> then);

		/**
		 * Makes the ticked changes of a list from the game's own records (Collection Log pages, the Boss Kill Log) that the player
		 * agreed to.
		 *
		 * @return {kill counts changed, copies added, copies taken away, ticked lines left out because their boss changed since}
		 */
		default int[] applySync(HistoryPlanner.Plan plan)
		{
			return new int[4];
		}

		/**
		 * The player dismissed the list from the game's own records: what was read is forgotten.
		 */
		default void dismissSync()
		{
		}

		void exportBackup(Component parent);

		/**
		 * Asks for a backup file and reads it; the callback runs on the Swing thread with the backup.
		 */
		void importBackup(Component parent, Consumer<Ledger> confirm);

		void replaceLedger(Ledger backup);

		/**
		 * The player started to track an item: its earlier copies are brought in from the drop log.
		 */
		default void backfill(String bossId, String item)
		{
		}

		/**
		 * The ledger was erased: the drop log goes too.
		 */
		default void eraseDropLog()
		{
		}
	}

	private static final int NOTE_MS = 8000;
	private static final String OVERVIEW = "overview";
	private static final String BOSS = "boss";
	private static final String SETTINGS = "settings";
	private static final String NONE = "none";

	private final Host host;
	private final Dialogs dialogs;
	private final Jobs jobs;
	private final Ctx ctx = new Context();

	private final JButton back = Look.link("Overview");
	private final JLabel title = Look.label("Drop Luck Ledger", Look.bold(), Look.TEXT);
	private final JButton settingsButton = Look.link("Settings");
	private final Column notices = new Column(4);
	private final Column body = new Column(0);
	private final JScrollPane scroll;
	private final Map<String, Notice> keyed = new LinkedHashMap<>();
	private final List<Notice> shown = new ArrayList<>();
	private final AtomicBoolean refreshQueued = new AtomicBoolean();
	private final Set<String> changedBosses = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile boolean changedAll;
	private volatile boolean quiet;

	private Ledger ledger;
	private ItemIcons icons;
	private HistoryPlanner planner;
	private Analyzer analyzer;
	private final Ledger.Listener listener = this::ledgerChanged;
	private OverviewPage overview;
	private BossPage bossPage;
	private SettingsPage settingsPage;
	private String view = NONE;
	private String settingsFrom = OVERVIEW;
	private String settingsFromBoss;
	private boolean settingsOpen;
	private boolean wasTracking;
	private boolean lastPictures = true;
	private Notice stateNote;
	private HistoryPlanner.Plan offered;
	/**
	 * The list from the game's own records the "sync" note offers, or null.
	 */
	private HistoryPlanner.Plan syncPlan;

	public LedgerPanel(Host host, ExecutorService analysis)
	{
		this(host, analysis, new Dialogs.Swing());
	}

	LedgerPanel(Host host, ExecutorService analysis, Dialogs dialogs)
	{
		super(false);
		this.host = host;
		this.dialogs = dialogs;
		this.jobs = new Jobs(analysis);

		setLayout(new BorderLayout());
		setBackground(Look.BG);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		back.setIcon(Glyph.of(Glyph.Kind.LEFT, Look.ACCENT));
		back.setIconTextGap(3);
		back.addActionListener(e -> goBack());
		settingsButton.addActionListener(e -> openSettings());
		JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		west.setOpaque(false);
		west.add(back);
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.setOpaque(false);
		east.add(settingsButton);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		header.add(west, BorderLayout.WEST);
		header.add(title, BorderLayout.CENTER);
		header.add(east, BorderLayout.EAST);

		Column top = new Column(4);
		top.setBorder(Look.pad(6, 6, 4, 6));
		top.add(header);
		top.add(notices);
		add(top, BorderLayout.NORTH);

		body.setBorder(Look.pad(2, 6, 8, 6));
		scroll = new JScrollPane(body);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(Look.BG);
		scroll.setBackground(Look.BG);
		add(scroll, BorderLayout.CENTER);

		updateHeader();
		stateChanged();
	}

	// ------------------------------------------------------------------ what the plugin tells the panel

	/**
	 * The boss data and the helpers are ready.  The panel follows the ledger from now on.
	 */
	public void attach(Ledger ledger, BossIndex index, ItemIcons icons, HistoryPlanner planner)
	{
		onEdt(() ->
		{
			detachLedger();
			this.ledger = ledger;
			this.icons = icons;
			this.planner = planner;
			this.analyzer = new Analyzer(ledger, icons);
			this.lastPictures = pictures();
			ledger.addListener(listener);
			stateChanged();
		});
	}

	/**
	 * The plugin is switched off.  The panel is not used again.
	 */
	public void detach()
	{
		onEdt(() ->
		{
			jobs.close();
			detachLedger();
			for (Notice n : new ArrayList<>(shown))
			{
				n.stop();
			}
			shown.clear();
			keyed.clear();
			notices.removeAll();
			body.removeAll();
			view = NONE;
		});
	}

	private void detachLedger()
	{
		if (ledger != null)
		{
			ledger.removeListener(listener);
		}
		ledger = null;
		analyzer = null;
		overview = null;
		bossPage = null;
		settingsPage = null;
		wasTracking = false;
	}

	/**
	 * The plugin's state changed (logged in, logged out, paused ...): show the right thing.
	 */
	public void stateChanged()
	{
		onEdt(() ->
		{
			TrackingState s = host.state();
			boolean tracking = s == TrackingState.TRACKING && ledger != null;
			if (stateNote != null)
			{
				stateNote.dismiss();
				stateNote = null;
			}
			if (tracking)
			{
				if (!wasTracking)
				{
					wasTracking = true;
					openOverview();
				}
			}
			else
			{
				wasTracking = false;
				view = NONE;
				bossPage = null;
				settingsPage = null;
				body.removeAll();
				body.revalidate();
				body.repaint();
				String text = s == TrackingState.TRACKING ? TrackingState.LOADING.message() : s.message();
				stateNote = notice(null, text, Look.MUTED, 0, false);
				offered = null;
				// a list read for this account is not offered to the next one
				Notice sync = keyed.get("sync");
				if (sync != null)
				{
					sync.dismiss();
				}
				syncPlan = null;
			}
			updateHeader();
		});
	}

	/**
	 * The game has history that could be added to an empty ledger: say so, and let the player look at it first.
	 */
	public void offerHistory(HistoryPlanner.Plan plan)
	{
		onEdt(() ->
		{
			if (ledger == null)
			{
				return;
			}
			offered = plan;
			Notice n = notice("offer", "Your game has history that could be added: " + describe(plan) + ". Look at it first, nothing is added until you say so.", Look.ACCENT, 0, true);
			n.button("Look at it", () ->
			{
				HistoryPlanner.Plan p = offered;
				offered = null;
				n.dismiss();
				if (p != null)
				{
					showHistory(p);
				}
			});
			n.button("Not now", () ->
			{
				offered = null;
				n.dismiss();
			});
		});
	}

	/**
	 * The plugin read a page of the game's own records (kill log, collection log) and the ledger already matches it.
	 */
	public void historyRead(String text)
	{
		onEdt(() ->
		{
			if (ledger == null)
			{
				return;
			}
			notice("read", text, Look.BLUE, NOTE_MS, true);
		});
	}

	/**
	 * The game's own records read so far differ from the ledger: say how, and let the player add the changes, look at them first, or
	 * dismiss them.  Nothing changes until the player agrees.  A newer list replaces the note.
	 */
	public void syncReady(HistoryPlanner.Plan plan, String text)
	{
		onEdt(() ->
		{
			if (ledger == null)
			{
				return;
			}
			Notice read = keyed.get("read");
			if (read != null)
			{
				read.dismiss();
			}
			Notice n = notice("sync", text, Look.ACCENT, 0, true);
			syncPlan = plan;
			n.closed = () ->
			{
				syncPlan = null;
				host.dismissSync();
			};
			if (plan.kcToApply() + plan.itemRowsToApply() > 0)
			{
				n.button("Add", () ->
				{
					n.dismiss();
					applySync(plan);
				});
			}
			n.button("Review…", () -> reviewSync(n, plan));
		});
	}

	private void reviewSync(Notice n, HistoryPlanner.Plan plan)
	{
		HistoryForm form = new HistoryForm(plan);
		if (dialogs.form(window(), "Check against the game", form, "Apply"))
		{
			// a list made while the form was open holds this one's reads too: adding this one leaves the rest for a new note
			n.dismiss();
			applySync(plan);
		}
	}

	private void applySync(HistoryPlanner.Plan plan)
	{
		if (syncPlan == plan)
		{
			syncPlan = null;
		}
		int[] done = host.applySync(plan);
		List<String> parts = new ArrayList<>();
		if (done[0] > 0)
		{
			parts.add(done[0] + " kill count" + (done[0] == 1 ? "" : "s") + " set");
		}
		if (done[1] > 0)
		{
			parts.add(done[1] + " item" + (done[1] == 1 ? "" : "s") + " added");
		}
		if (done[2] > 0)
		{
			parts.add(done[2] + " item" + (done[2] == 1 ? "" : "s") + " taken away");
		}
		int left = done.length > 3 ? done[3] : 0;
		String text;
		if (parts.isEmpty())
		{
			text = left == 0 ? "Nothing needed changing." : "Nothing was changed: the boss changed after the list was made. Open its page again to check it.";
		}
		else
		{
			text = "From the game's records: " + String.join(", ", parts) + "."
				+ (left == 0 ? "" : " " + left + (left == 1 ? " line was" : " lines were") + " left out, as the boss changed after the list was made; open its page again to check it.");
		}
		notice(null, text, Look.GOOD, NOTE_MS, true);
	}

	/**
	 * The history the panel is offering at the moment, or null.
	 */
	public HistoryPlanner.Plan offeredPlan()
	{
		return offered;
	}

	/**
	 * The list from the game's own records the panel is offering at the moment, or null.
	 */
	public HistoryPlanner.Plan syncPlan()
	{
		return keyed.containsKey("sync") ? syncPlan : null;
	}

	/**
	 * Shows a short note that something was done (it goes away by itself).
	 */
	public void showDone(String text)
	{
		onEdt(() -> notice(null, text, Look.GOOD, NOTE_MS, true));
	}

	/**
	 * Shows a note from the plugin at the top of the panel, whatever the panel is showing.  It stays until it is dismissed: these
	 * are the things the player should not miss (a ledger that could not be read, where a backup went).
	 */
	public void showMessage(String text)
	{
		onEdt(() -> notice(null, text, Look.ACCENT, 0, true));
	}

	private static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	private static String describe(HistoryPlanner.Plan plan)
	{
		int k = plan.kcToApply();
		int c = plan.copiesToAdd();
		int back = plan.copiesToRemove();
		List<String> parts = new ArrayList<>();
		if (k > 0)
		{
			parts.add(k + " kill count" + (k == 1 ? "" : "s"));
		}
		if (c > 0)
		{
			parts.add(c + " owned item" + (c == 1 ? "" : "s"));
		}
		if (back > 0)
		{
			parts.add("a correction of " + back + " item" + (back == 1 ? "" : "s") + " counted too many times");
		}
		return parts.isEmpty() ? "nothing new" : String.join(" and ", parts);
	}

	// ------------------------------------------------------------------ pages

	private void goBack()
	{
		if (SETTINGS.equals(view))
		{
			if (BOSS.equals(settingsFrom) && settingsFromBoss != null)
			{
				openBoss(settingsFromBoss);
			}
			else
			{
				openOverview();
			}
		}
		else
		{
			openOverview();
		}
	}

	private void openOverview()
	{
		if (ledger == null)
		{
			return;
		}
		if (overview == null)
		{
			overview = new OverviewPage(ctx);
		}
		else
		{
			overview.refresh();
		}
		bossPage = null;
		settingsPage = null;
		view = OVERVIEW;
		show(overview);
	}

	private void openBoss(String id)
	{
		if (ledger == null || ledger.data().boss(id) == null)
		{
			return;
		}
		ctx.remember(id, ledger.prefs().tab, ledger.prefs().scope);
		bossPage = new BossPage(ctx, id, settingsOpen, open -> settingsOpen = open);
		settingsPage = null;
		view = BOSS;
		show(bossPage);
	}

	private void openSettings()
	{
		if (ledger == null || SETTINGS.equals(view))
		{
			return;
		}
		settingsFrom = view;
		settingsFromBoss = bossPage == null ? null : bossPage.bossId();
		settingsPage = new SettingsPage(ctx);
		view = SETTINGS;
		show(settingsPage);
	}

	private void show(JPanel page)
	{
		body.removeAll();
		body.add(page);
		body.revalidate();
		body.repaint();
		updateHeader();
		SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
	}

	private void updateHeader()
	{
		boolean tracking = ledger != null && host.state() == TrackingState.TRACKING;
		back.setVisible(tracking && !OVERVIEW.equals(view) && !NONE.equals(view));
		settingsButton.setVisible(tracking && !SETTINGS.equals(view));
		back.setText(SETTINGS.equals(view) ? "Back" : "Overview");
		String t = SETTINGS.equals(view) ? "Settings" : "Drop Luck Ledger";
		title.setText(t);
		title.setToolTipText(t);
	}

	// ------------------------------------------------------------------ following the ledger

	private void ledgerChanged(String bossId)
	{
		if (quiet && SwingUtilities.isEventDispatchThread())
		{
			return;
		}
		if (bossId == null)
		{
			changedAll = true;
		}
		else
		{
			changedBosses.add(bossId);
		}
		if (refreshQueued.compareAndSet(false, true))
		{
			SwingUtilities.invokeLater(this::flushChanges);
		}
	}

	private void flushChanges()
	{
		refreshQueued.set(false);
		final boolean all = changedAll;
		changedAll = false;
		final Set<String> ids = new HashSet<>(changedBosses);
		changedBosses.removeAll(ids);
		if (ledger == null)
		{
			return;
		}
		boolean pics = pictures();
		if (pics != lastPictures)
		{
			lastPictures = pics;
			rebuildCurrent();
			return;
		}
		if (OVERVIEW.equals(view) && overview != null)
		{
			overview.refresh();
		}
		else if (BOSS.equals(view) && bossPage != null)
		{
			if (all || ids.contains(bossPage.bossId()))
			{
				bossPage.refresh();
			}
		}
		else if (SETTINGS.equals(view) && settingsPage != null && all)
		{
			settingsPage.sync();
		}
		updateHeader();
	}

	/**
	 * Builds the page that is showing again (item pictures were switched on or off).
	 */
	private void rebuildCurrent()
	{
		if (BOSS.equals(view) && bossPage != null)
		{
			String id = bossPage.bossId();
			String tab = bossPage.tab();
			openBoss(id);
			bossPage.selectTab(tab);
		}
		else if (SETTINGS.equals(view))
		{
			SettingsPage old = settingsPage;
			if (old != null)
			{
				old.sync();
			}
		}
		else if (OVERVIEW.equals(view))
		{
			overview = new OverviewPage(ctx);
			show(overview);
		}
	}

	private boolean pictures()
	{
		return ledger == null || ledger.prefs().images != 0;
	}

	// ------------------------------------------------------------------ game history

	private void reviewHistoryNow()
	{
		if (ledger == null || host.state() != TrackingState.TRACKING)
		{
			dialogs.note(window(), "Game history", "Log in to a character first, then try again.");
			return;
		}
		host.planHistory(plan -> onEdt(() -> showHistory(plan)));
	}

	private void showHistory(HistoryPlanner.Plan plan)
	{
		if (ledger == null || planner == null)
		{
			return;
		}
		if (plan.sources.isEmpty() && plan.kc.isEmpty() && plan.items.isEmpty())
		{
			dialogs.note(window(), "Game history", "RuneLite has saved nothing to bring in for this character.\n\nOpen your Boss Kill Log (in the Adventure Log) "
				+ "and your Collection Log pages in game: the panel offers what they show.");
			return;
		}
		if (plan.isEmpty())
		{
			dialogs.note(window(), "Game history", "Your ledger already has everything RuneLite saved.");
			return;
		}
		HistoryForm form = new HistoryForm(plan);
		if (dialogs.form(window(), "Add my game history", form, "Add to ledger"))
		{
			int[] done = planner.apply(plan);
			String text = "Added " + done[0] + " kill count" + (done[0] == 1 ? "" : "s") + " and " + done[1] + " owned item" + (done[1] == 1 ? "" : "s")
					+ (done[2] > 0 ? "; took back " + done[2] : "");
			notice(null, text, Look.GOOD, NOTE_MS, true);
			Notice read = keyed.get("read");
			if (read != null)
			{
				read.dismiss();
			}
		}
	}

	private Component window()
	{
		Component w = SwingUtilities.getWindowAncestor(this);
		return w != null ? w : this;
	}

	// ------------------------------------------------------------------ notes at the top

	/**
	 * A note under the header.  A note with a key replaces an earlier note with the same key.
	 *
	 * @param autoMs 0 to keep it until it is dismissed
	 */
	private Notice notice(String key, String text, Color accent, int autoMs, boolean closable)
	{
		if (key != null)
		{
			Notice old = keyed.get(key);
			if (old != null)
			{
				old.dismiss();
			}
		}
		Notice n = new Notice(key, text, accent, autoMs, closable);
		if (key != null)
		{
			keyed.put(key, n);
		}
		shown.add(n);
		notices.add(n.panel, 0);
		while (shown.size() > 4)
		{
			// the oldest note goes, but not one the player still has to answer (what the game's records would change, history on offer)
			Notice oldest = shown.get(0);
			for (Notice x : shown)
			{
				if (!"sync".equals(x.key) && !"offer".equals(x.key))
				{
					oldest = x;
					break;
				}
			}
			oldest.dismiss();
		}
		notices.revalidate();
		notices.repaint();
		return n;
	}

	/**
	 * One note.
	 */
	private final class Notice
	{
		final String key;
		final String text;
		final JPanel panel = new JPanel(new BorderLayout(4, 2));
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		/**
		 * Run when the player closes the note with its cross (not when a newer note replaces it).
		 */
		Runnable closed;
		/**
		 * The cross, or null for a note that cannot be closed.
		 */
		JButton cross;
		private Timer timer;

		Notice(String key, String text, Color accent, int autoMs, boolean closable)
		{
			this.key = key;
			this.text = text;
			panel.setBackground(Look.CARD);
			panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent), Look.pad(5, 6, 5, 4)));
			JLabel l = Look.wrapped(Look.esc(text), 170, Look.small(), Look.TEXT);
			panel.add(l, BorderLayout.CENTER);
			if (closable)
			{
				JButton x = new JButton(Glyph.of(Glyph.Kind.CROSS));
				x.setBorderPainted(false);
				x.setContentAreaFilled(false);
				x.setFocusPainted(false);
				x.setMargin(new java.awt.Insets(0, 0, 0, 0));
				x.setToolTipText("Dismiss");
				cross = x;
				x.addActionListener(e ->
				{
					dismiss();
					if (closed != null)
					{
						closed.run();
					}
				});
				JPanel nx = new JPanel(new BorderLayout());
				nx.setOpaque(false);
				nx.add(x, BorderLayout.NORTH);
				panel.add(nx, BorderLayout.EAST);
			}
			buttons.setOpaque(false);
			panel.add(buttons, BorderLayout.SOUTH);
			if (autoMs > 0)
			{
				timer = new Timer(autoMs, e -> dismiss());
				timer.setRepeats(false);
				timer.start();
			}
		}

		void button(String label, Runnable action)
		{
			JButton b = Look.button(label);
			b.addActionListener(e -> action.run());
			buttons.add(b);
			panel.revalidate();
		}

		void stop()
		{
			if (timer != null)
			{
				timer.stop();
				timer = null;
			}
		}

		void dismiss()
		{
			stop();
			shown.remove(this);
			if (key != null && keyed.get(key) == this)
			{
				keyed.remove(key);
			}
			notices.remove(panel);
			notices.revalidate();
			notices.repaint();
		}
	}

	// ------------------------------------------------------------------ what the parts of the panel can ask for

	private final class Context implements Ctx
	{
		@Override
		public Ledger ledger()
		{
			return ledger;
		}

		@Override
		public Analyzer analyzer()
		{
			return analyzer;
		}

		@Override
		public Jobs jobs()
		{
			return jobs;
		}

		@Override
		public ItemIcons icons()
		{
			return icons;
		}

		@Override
		public HistoryPlanner planner()
		{
			return planner;
		}

		@Override
		public Host host()
		{
			return host;
		}

		@Override
		public Dialogs dialogs()
		{
			return dialogs;
		}

		@Override
		public boolean pictures()
		{
			return LedgerPanel.this.pictures();
		}

		@Override
		public Component window()
		{
			return LedgerPanel.this.window();
		}

		@Override
		public void openBoss(String bossId)
		{
			LedgerPanel.this.openBoss(bossId);
		}

		@Override
		public void openOverview()
		{
			LedgerPanel.this.openOverview();
		}

		@Override
		public void openSettings()
		{
			LedgerPanel.this.openSettings();
		}

		@Override
		public void notice(String text)
		{
			LedgerPanel.this.notice(null, text, Look.GOOD, NOTE_MS, true);
		}

		@Override
		public void remember(String boss, String tab, String scope)
		{
			quiet = true;
			try
			{
				if (ledger != null)
				{
					ledger.setView(boss, tab, scope);
				}
			}
			finally
			{
				quiet = false;
			}
		}

		@Override
		public void reviewHistory()
		{
			reviewHistoryNow();
		}
	}

	// ------------------------------------------------------------------ for tests

	Ctx context()
	{
		return ctx;
	}

	/**
	 * What the panel is showing: "overview", "boss", "settings", or "none" while nothing is recorded.
	 */
	public String viewName()
	{
		return view;
	}

	OverviewPage overviewPage()
	{
		return overview;
	}

	BossPage bossPage()
	{
		return bossPage;
	}

	SettingsPage settingsPage()
	{
		return settingsPage;
	}

	Column bodyColumn()
	{
		return body;
	}

	boolean idle()
	{
		return jobs.idle();
	}

	JLabel titleLabel()
	{
		return title;
	}

	JButton backButton()
	{
		return back;
	}

	JButton settingsLink()
	{
		return settingsButton;
	}

	/**
	 * The text of each note showing, newest first.
	 */
	public List<String> noteTexts()
	{
		List<String> out = new ArrayList<>();
		for (Notice n : shown)
		{
			out.add(n.text);
		}
		Collections.reverse(out);
		return out;
	}

	/**
	 * The buttons of the note with the given key (or the first note when the key is null).
	 */
	List<JButton> noteButtons(String key)
	{
		Notice n = key == null ? (shown.isEmpty() ? null : shown.get(shown.size() - 1)) : keyed.get(key);
		List<JButton> out = new ArrayList<>();
		if (n != null)
		{
			for (Component c : n.buttons.getComponents())
			{
				out.add((JButton) c);
			}
		}
		return out;
	}

	/**
	 * The cross of the note with the given key, or null.
	 */
	JButton noteCross(String key)
	{
		Notice n = keyed.get(key);
		return n == null ? null : n.cross;
	}

	/**
	 * The explanation shown while nothing is recorded ("Log in to ..."), or an empty text.
	 */
	public String stateText()
	{
		return stateNote == null ? "" : stateNote.text;
	}
}
