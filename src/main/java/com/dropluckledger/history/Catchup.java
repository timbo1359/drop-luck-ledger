package com.dropluckledger.history;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.history.HistoryPlanner.ItemRow;
import com.dropluckledger.history.HistoryPlanner.KcRow;
import com.dropluckledger.history.HistoryPlanner.Plan;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Checks the ledger against the game's own records as the player opens them and lists what they would change.  Nothing changes until
 * the player agrees: the list builds up over the records read since the player last added or dismissed it, so a run through several
 * pages makes one list.
 * <ul>
 * <li>Kill counts, from the Boss Kill Log and from the counts under a Collection Log page's title.  (The caller hands over a page's
 * counts as the game holds them once the page has settled, not as first drawn: the game can draw a page with the previous page's
 * numbers.)  When the records show every kill-count label of a boss, the ledger is set to their total, up or down, but never below the
 * kill of the latest drop with a kill number, which a note then points out; the latest look at each label counts.  When the Boss Kill
 * Log shows only some labels, it can only add the kills they show ({@link Capture#onShownCount}), and not at all when it leaves out the
 * awakened kills of a boss, as its other count may or may not hold them.  A page that shows only some of a boss's labels (the Desert
 * Treasure II bosses' pages have no awakened count) is not used for its kill count.</li>
 * <li>Items, from Collection Log pages.  A tracked item a page shows more copies of than the ledger has gets the missing copies with no
 * kill number, by the rules of <i>Add my game history…</i> ({@link HistoryPlanner}); an item several bosses drop that the page does not
 * pin to one boss is listed but not ticked.  An item the ledger has more copies of than the page shows loses the extra copies, when the
 * page's count is the number of times the item dropped: not an item that drops several at a time (the game counts how many were
 * received), not a pet (the ledger also counts a pet that dropped while the player already had it, which the game may not), and not a
 * count that may have stopped at the item's cap ({@link #MAY_BE_CAPPED}).  Extra copies of an item several bosses hold are pointed
 * out, as it is not known which boss's are wrong.  Items the player does not track are left alone.  The caller only hands over a page
 * whose items it has checked against what the game holds for it.</li>
 * </ul>
 * <p>
 * A read only speaks for a boss until the boss changes in the ledger (a kill, a drop, an edit by hand): after that its numbers are
 * older than the ledger's, so it is no longer used for that boss.  Likewise a list only adds the lines of bosses that have not changed
 * since it was made.
 */
public final class Catchup
{
	private static final String LOG = "Collection Log";
	private static final String KILL_LOG = "Boss Kill Log";
	private static final String KILL_LOG_KEY = "|kill log";
	private static final int LISTED = 3;
	/**
	 * The collection log stops counting some items at a cap that depends on the item: the wiki names 65,535 for Zulrah's scales
	 * and 250 for bracelets of ethereum, and has no full list.  A count this high may have stopped at its cap, so it never takes
	 * copies away.
	 */
	static final int MAY_BE_CAPPED = 250;

	/**
	 * One look at a page of the game's records.
	 */
	private static final class Read
	{
		/**
		 * The collection log page's title, or null for the Boss Kill Log.
		 */
		final String page;
		/**
		 * The counts shown, as the game words the activities: the Boss Kill Log's rows, or the lines under a page's title.
		 */
		final Map<String, Integer> counts;
		/**
		 * A page's items, as the game names them, to copies owned (empty for the Boss Kill Log).
		 */
		final Map<String, Integer> items;
		/**
		 * Every item on the page, owned or not.
		 */
		final Set<String> listed;
		/**
		 * Boss id to the ledger's revision of it when this was read.
		 */
		final Map<String, Long> revisions;

		Read(String page, Map<String, Integer> counts, Map<String, Integer> items, Collection<String> listed, Map<String, Long> revisions)
		{
			this.page = page;
			this.counts = new LinkedHashMap<>(counts);
			this.items = new LinkedHashMap<>(items);
			this.listed = new LinkedHashSet<>(listed);
			this.revisions = revisions;
		}
	}

	/**
	 * What a list was made from: the reads, and the ledger's revision of each boss then.
	 */
	private static final class Basis
	{
		final List<Read> reads;
		final Map<String, Long> revisions;

		Basis(List<Read> reads, Map<String, Long> revisions)
		{
			this.reads = reads;
			this.revisions = revisions;
		}
	}

	private final Ledger ledger;
	private final Capture capture;
	private final HistoryPlanner planner;
	private final BossIndex index;
	private final Data data;
	/**
	 * The records read since the last time the player added or dismissed them, the latest look at each page last.
	 */
	private final LinkedHashMap<String, Read> reads = new LinkedHashMap<>();
	/**
	 * The reads each list still on show was made from: adding a list lets go of those, not of reads that came after it.
	 */
	private final Map<Plan, Basis> plannedFrom = new WeakHashMap<>();

	public Catchup(Ledger ledger, Capture capture, HistoryPlanner planner, BossIndex index)
	{
		this.ledger = ledger;
		this.capture = capture;
		this.planner = planner;
		this.index = index;
		this.data = ledger.data();
	}

	/**
	 * The ledger's revision of every boss at one moment.  A read is stamped with the moment the game sent its numbers (they are on
	 * screen a little later), so a kill or a drop the ledger took in between makes it older than the ledger for that boss.
	 */
	public static final class Stamp
	{
		private final Map<String, Long> revisions;

		private Stamp(Map<String, Long> revisions)
		{
			this.revisions = revisions;
		}
	}

	/**
	 * The ledger as it is now, to stamp a read with.
	 */
	public synchronized Stamp stamp()
	{
		return new Stamp(revisions());
	}

	/**
	 * True when no boss a page speaks for has changed since the stamp: the bosses whose collection log lists one of its items, and the
	 * bosses of its counts.
	 *
	 * @param gameNames the page's items, as the game names them
	 * @param counts    the page's counts, as the game words them
	 */
	public synchronized boolean unchangedSince(Stamp then, Collection<String> gameNames, Collection<String> counts)
	{
		for (String name : gameNames)
		{
			String item = planner.itemFor(name);
			if (item != null && !unchanged(then.revisions, planner.bossesFor(item)))
			{
				return false;
			}
		}
		for (String shown : counts)
		{
			KcLabel kl = index.kcShown(shown);
			if (kl != null && !unchanged(then.revisions, Collections.singletonList(kl.bossId)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * A collection log page that has settled.
	 *
	 * @param counts the counts under its title, as the game words them, with the values the game holds for them now
	 * @param items  item (as the game names it) to copies owned
	 * @param listed every item on the page, owned or not
	 * @param drawn  the ledger as it was when the page was drawn
	 */
	public synchronized void addPage(String title, Map<String, Integer> counts, Map<String, Integer> items, Collection<String> listed, Stamp drawn)
	{
		String key = "page|" + title;
		reads.remove(key);
		reads.put(key, new Read(title, counts, items, listed, new HashMap<>(drawn.revisions)));
	}

	/**
	 * The Boss Kill Log: activity as the screen wrote it, to the count.
	 *
	 * @param opened the ledger as it was when the log opened (the server sends its counts with it)
	 */
	public synchronized void addKillLog(Map<String, Integer> counts, Stamp opened)
	{
		reads.remove(KILL_LOG_KEY);
		reads.put(KILL_LOG_KEY, new Read(null, counts, Collections.<String, Integer>emptyMap(), Collections.<String>emptyList(),
			new HashMap<>(opened.revisions)));
	}

	/**
	 * The ledger's revision of every boss now.
	 */
	private Map<String, Long> revisions()
	{
		Map<String, Long> out = new HashMap<>();
		for (Boss b : data.bosses)
		{
			out.put(b.id, ledger.revision(b.id));
		}
		return out;
	}

	/**
	 * True when none of the bosses has changed in the ledger since the given revisions were taken.
	 */
	private boolean unchanged(Map<String, Long> then, Collection<String> bossIds)
	{
		for (String id : bossIds)
		{
			Long rev = then.get(id);
			if (rev == null || rev != ledger.revision(id))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Forgets what was read (the player dismissed it, or another account logged in).
	 */
	public synchronized void clear()
	{
		reads.clear();
		plannedFrom.clear();
	}

	// ------------------------------------------------------------------ the list

	/**
	 * What the records read so far would change, against the ledger as it is now.
	 */
	public synchronized Plan plan()
	{
		Plan plan = new Plan();
		plannedFrom.put(plan, new Basis(new ArrayList<>(reads.values()), revisions()));
		List<String> pages = new ArrayList<>();
		for (Read r : reads.values())
		{
			if (r.page == null)
			{
				plan.sources.add(KILL_LOG);
			}
			else
			{
				pages.add(r.page);
			}
		}
		if (!pages.isEmpty())
		{
			plan.sources.add(LOG + " (" + (pages.size() <= LISTED ? String.join(", ", pages)
				: String.join(", ", pages.subList(0, LISTED - 1)) + " and " + (pages.size() - LISTED + 1) + " more pages") + ")");
		}
		killCounts(plan);
		addedItems(plan);
		extraItems(plan);
		plan.kc.sort(Comparator.comparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
		plan.items.sort(Comparator.<ItemRow, String>comparing(r -> r.bossName, String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.item));
		return plan;
	}

	private List<String> labelNames(String bossId)
	{
		List<String> out = new ArrayList<>();
		for (KcLabel kl : index.labels(bossId))
		{
			out.add(kl.label);
		}
		return out;
	}

	/**
	 * True when a boss's awakened kills are not among the counts shown: then the other count may or may not hold them.
	 */
	private boolean hidesAwakened(String bossId, Collection<String> shown)
	{
		for (String label : labelNames(bossId))
		{
			if (label.startsWith("Awakened ") && !shown.contains(label))
			{
				return true;
			}
		}
		return false;
	}

	private void killCounts(Plan plan)
	{
		// each label's latest count, over the reads that still speak for its boss
		Map<String, Map<String, Integer>> byBoss = new LinkedHashMap<>();
		for (Read r : reads.values())
		{
			Map<String, Map<String, Integer>> found = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> e : r.counts.entrySet())
			{
				KcLabel kl = e.getValue() == null || e.getValue() < 0 ? null : index.kcShown(e.getKey());
				if (kl != null)
				{
					found.computeIfAbsent(kl.bossId, k -> new LinkedHashMap<>()).merge(kl.label, e.getValue(), Math::max);
				}
			}
			for (Map.Entry<String, Map<String, Integer>> e : found.entrySet())
			{
				Boss b = data.boss(e.getKey());
				Set<String> shown = e.getValue().keySet();
				// every claimed reward is one run (the game's own count measures something else); a boss that changed after the read has
				// a newer count than the read's; a page with only some of a boss's counts does not say what the others are; and a log
				// without a boss's awakened kills may or may not count them elsewhere
				if (b == null || b.lootIsClaim() || !unchanged(r.revisions, Collections.singletonList(b.id))
					|| !shown.containsAll(labelNames(b.id)) && (r.page != null || hidesAwakened(b.id, shown)))
				{
					continue;
				}
				byBoss.computeIfAbsent(b.id, k -> new LinkedHashMap<>()).putAll(e.getValue());
			}
		}
		for (Map.Entry<String, Map<String, Integer>> e : byBoss.entrySet())
		{
			String id = e.getKey();
			if (capture.awaitingLoot(id))
			{
				continue; // a kill's loot is still to come and takes the count as it is then: the next look catches up
			}
			Map<String, Integer> labels = e.getValue();
			BossState s = ledger.copyOf(id);
			int cur = s.kc;
			String name = data.boss(id).name;
			if (labels.keySet().containsAll(labelNames(id)))
			{
				int total = 0;
				for (int n : labels.values())
				{
					total += n;
				}
				int floor = s.maxDropKc();
				if (total > cur)
				{
					plan.kc.add(new KcRow(id, name, cur, total, labels));
				}
				else if (total < cur && Math.max(total, floor) < cur)
				{
					plan.kc.add(new KcRow(id, name, cur, Math.max(total, floor), labels, true));
				}
				if (total < cur && floor > total)
				{
					plan.notes.add(name + ": the game counts " + count(total) + (total == 1 ? " kill" : " kills") + ", but the ledger has a drop at kill "
						+ count(floor) + ", so its kill count cannot go below that. If that kill number is wrong, change it on the boss's page.");
				}
				continue;
			}
			// only some of the boss's counts: the kills they add, as when they are seen live
			for (Map.Entry<String, Integer> l : labels.entrySet())
			{
				if (l.getValue() > 0)
				{
					Capture.raiseLocked(s, l.getKey(), l.getValue());
				}
			}
			if (s.kc > cur)
			{
				plan.kc.add(new KcRow(id, name, cur, s.kc, labels));
			}
		}
	}

	private void addedItems(Plan plan)
	{
		HistoryInput in = new HistoryInput();
		for (Read r : reads.values())
		{
			for (Map.Entry<String, Integer> e : r.items.entrySet())
			{
				if (current(r, e.getKey()))
				{
					in.addOwned(null, r.page, e.getKey(), e.getValue(), LOG);
				}
			}
		}
		if (in.owned.isEmpty())
		{
			return;
		}
		Plan items = planner.plan(in);
		for (ItemRow row : items.items)
		{
			if (row.add <= 0 || !ledger.trackedNames(row.bossId).contains(row.item))
			{
				continue;
			}
			if (row.assumed)
			{
				row.on = false; // several bosses drop it and the page did not say which: the player decides
			}
			plan.items.add(row);
		}
		plan.itemsMissing.addAll(items.itemsMissing);
	}

	/**
	 * True when no boss whose collection log lists an item (named as the game names it) has changed since the read.
	 */
	private boolean current(Read r, String gameName)
	{
		String item = planner.itemFor(gameName);
		return item == null || unchanged(r.revisions, planner.bossesFor(item));
	}

	/**
	 * The copies of tracked items that the ledger has more of than the pages show.  A page's count of an item is the game's count over
	 * every boss that drops it, so it is set against the copies all of them hold.
	 */
	private void extraItems(Plan plan)
	{
		// the count the latest page listing each item shows (0 when it is drawn faded)
		Map<String, Integer> shown = new LinkedHashMap<>();
		for (Read r : reads.values())
		{
			// a page that draws every item faded may have been drawn before its numbers came: it takes nothing away
			if (r.items.isEmpty())
			{
				continue;
			}
			Map<String, Integer> here = new LinkedHashMap<>();
			for (String name : r.listed)
			{
				String item = planner.itemFor(name);
				if (item != null && current(r, name))
				{
					Integer n = r.items.get(name);
					here.merge(item, n == null ? 0 : n, Math::max);
				}
			}
			shown.putAll(here);
		}
		// each boss's state is looked at for many items: copied once
		Map<String, BossState> copies = new HashMap<>();
		for (Map.Entry<String, Integer> e : shown.entrySet())
		{
			String item = e.getKey();
			int game = e.getValue();
			if (game >= MAY_BE_CAPPED)
			{
				continue;
			}
			List<String> holders = new ArrayList<>();
			Map<String, BossState> states = new LinkedHashMap<>();
			int held = 0;
			boolean dropCount = true;
			for (String id : planner.bossesFor(item))
			{
				BossState s = copies.computeIfAbsent(id, ledger::copyOf);
				int n = s.copies(item);
				if (n == 0)
				{
					continue;
				}
				Boss boss = data.boss(id);
				dropCount &= ledger.trackedNames(id).contains(item) && !"pet".equals(Catalog.kindOf(boss, item)) && Quantities.oneAtATime(boss, item);
				holders.add(id);
				states.put(id, s);
				held += n;
			}
			int extra = held - game;
			if (!dropCount || extra <= 0)
			{
				continue;
			}
			// copies a history import added with no kill number are guesses wherever they are; the import puts an item it cannot pin
			// to one boss at the one with the most kills, so the other bosses lose theirs first
			holders.sort(Comparator.comparingInt(id -> states.get(id).kc));
			Map<String, Integer> imported = new LinkedHashMap<>();
			for (String id : holders)
			{
				int take = Math.min(extra, states.get(id).importedUnknown(item));
				if (take > 0)
				{
					imported.put(id, take);
					extra -= take;
				}
			}
			List<String> still = new ArrayList<>();
			for (String id : holders)
			{
				if (states.get(id).copies(item) > imported.getOrDefault(id, 0))
				{
					still.add(id);
				}
			}
			if (extra > 0 && still.size() == 1)
			{
				String id = still.get(0);
				int take = extra + imported.getOrDefault(id, 0);
				imported.remove(id);
				plan.items.add(ItemRow.extra(id, data.boss(id).name, item, game, states.get(id).copies(item), take, false));
				extra = 0;
			}
			for (Map.Entry<String, Integer> t : imported.entrySet())
			{
				plan.items.add(ItemRow.extra(t.getKey(), data.boss(t.getKey()).name, item, game, states.get(t.getKey()).copies(item), t.getValue(), true));
			}
			if (extra > 0 && still.size() > 1)
			{
				List<String> names = new ArrayList<>();
				for (String id : still)
				{
					names.add(data.boss(id).name);
				}
				plan.notes.add("The ledger has " + extra + " more " + item + " than the collection log shows, split between " + join(names)
					+ ": remove the wrong " + (extra == 1 ? "copy on its boss page." : "copies on their boss pages."));
			}
		}
	}

	// ------------------------------------------------------------------ adding it

	/**
	 * Makes the ticked changes of a list, then forgets the reads it was made from.  Each line is checked against the ledger as it is now.
	 * A list made before the reads were forgotten (dismissed, or another account logged in) changes nothing.
	 *
	 * @return {kill counts changed, copies added, copies taken away, ticked lines left out because their boss changed after the list was
	 * made or a kill of it waits for its loot}
	 */
	public synchronized int[] apply(Plan plan)
	{
		Basis basis = plannedFrom.remove(plan);
		if (basis == null)
		{
			return new int[4];
		}
		// a boss that changed after the list was made (a kill, a drop) keeps what it has: the list's numbers are older than its own
		Set<String> changed = new HashSet<>();
		for (Map.Entry<String, Long> e : basis.revisions.entrySet())
		{
			if (e.getValue() != ledger.revision(e.getKey()))
			{
				changed.add(e.getKey());
			}
		}
		Map<String, Long> atStart = revisions();
		int kills = 0;
		int added = 0;
		int removed = 0;
		int left = 0;
		for (KcRow r : plan.kc)
		{
			if (!r.on || !r.changes())
			{
				continue;
			}
			if (changed.contains(r.bossId) || capture.awaitingLoot(r.bossId))
			{
				left++;
				continue;
			}
			if (r.lower)
			{
				kills += ledger.kcToGame(r.bossId, r.labels, r.to) ? 1 : 0;
				continue;
			}
			int before = ledger.kc(r.bossId);
			for (Map.Entry<String, Integer> e : r.labels.entrySet())
			{
				if (e.getValue() > 0)
				{
					capture.onShownCount(e.getKey(), e.getValue());
				}
			}
			if (r.labels.keySet().containsAll(labelNames(r.bossId)))
			{
				// the game's total of every kill is the count, whatever the ledger had seen of it before
				int total = 0;
				for (int n : r.labels.values())
				{
					total += n;
				}
				ledger.kcToGame(r.bossId, r.labels, total);
			}
			kills += ledger.kc(r.bossId) != before ? 1 : 0;
		}
		for (ItemRow r : plan.items)
		{
			if (!r.on || r.add <= 0 && r.remove <= 0)
			{
				continue;
			}
			if (changed.contains(r.bossId))
			{
				left++;
				continue;
			}
			if (r.extra)
			{
				removed += ledger.removeExtra(r.bossId, r.item, r.remove, r.importedOnly);
			}
			else if (r.add > 0)
			{
				int have = ledger.copies(r.bossId, r.item);
				added += ledger.addOwned(r.bossId, r.item, Math.max(0, Math.min(r.want, r.cap()) - have));
			}
		}
		for (Read r : basis.reads)
		{
			reads.values().remove(r);
		}
		// the reads left came from the same game as this list: what was just added does not make them older than the ledger
		for (Read r : reads.values())
		{
			for (Map.Entry<String, Long> e : atStart.entrySet())
			{
				if (e.getValue().equals(r.revisions.get(e.getKey())))
				{
					r.revisions.put(e.getKey(), ledger.revision(e.getKey()));
				}
			}
		}
		return new int[]{kills, added, removed, left};
	}

	// ------------------------------------------------------------------ the words

	/**
	 * One line for the player about a list: "Boss Kill Log, Collection Log (Skotizo): kill count Skotizo 117 → 5; Dark claw −1.  Nothing
	 * changes until you add them."  A list with nothing to change says its first note.
	 */
	public static String describe(Plan plan)
	{
		List<String> kills = new ArrayList<>();
		List<String> items = new ArrayList<>();
		int moreKills = 0;
		int moreItems = 0;
		int unticked = 0;
		for (KcRow r : plan.kc)
		{
			if (!r.changes())
			{
				continue;
			}
			if (!r.on)
			{
				unticked++;
			}
			else if (kills.size() < LISTED)
			{
				kills.add(r.name + " " + count(r.cur) + " → " + count(r.to));
			}
			else
			{
				moreKills++;
			}
		}
		for (ItemRow r : plan.items)
		{
			if (r.add <= 0 && r.remove <= 0)
			{
				continue;
			}
			if (!r.on)
			{
				unticked++;
			}
			else if (items.size() < LISTED)
			{
				items.add(r.item + (r.extra ? " −" + count(r.remove) : " +" + count(r.add)));
			}
			else
			{
				moreItems++;
			}
		}
		List<String> parts = new ArrayList<>();
		if (!kills.isEmpty())
		{
			parts.add((kills.size() + moreKills == 1 ? "kill count " : "kill counts ") + String.join(", ", kills)
				+ (moreKills > 0 ? " and " + moreKills + " more" : ""));
		}
		if (!items.isEmpty())
		{
			parts.add(String.join(", ", items) + (moreItems > 0 ? " and " + moreItems + " more item" + (moreItems == 1 ? "" : "s") : ""));
		}
		StringBuilder b = new StringBuilder(String.join(", ", plan.sources)).append(": ");
		if (parts.isEmpty())
		{
			if (unticked == 0 && !plan.notes.isEmpty())
			{
				b.append(plan.notes.get(0));
				int others = plan.notes.size() - 1;
				return others == 0 ? b.toString() : b.append(" Review it for ").append(others).append(others == 1 ? " more note." : " more notes.").toString();
			}
			b.append(unticked).append(" line").append(unticked == 1 ? "" : "s").append(" to look at");
			return b.append(". Review it to choose what to add.").toString();
		}
		b.append(String.join("; ", parts)).append('.');
		if (unticked > 0)
		{
			b.append(' ').append(unticked).append(" more line").append(unticked == 1 ? "" : "s").append(" to look at.");
		}
		int changes = kills.size() + moreKills + items.size() + moreItems;
		return b.append(" Nothing changes until you add ").append(changes == 1 ? "it." : "them.").toString();
	}

	/**
	 * True when a list holds anything for the player: a line that changes something, or a note.
	 */
	public static boolean worthShowing(Plan plan)
	{
		for (KcRow r : plan.kc)
		{
			if (r.changes())
			{
				return true;
			}
		}
		for (ItemRow r : plan.items)
		{
			if (r.add > 0 || r.remove > 0)
			{
				return true;
			}
		}
		return !plan.notes.isEmpty();
	}

	private static String count(int n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static String join(List<String> l)
	{
		if (l.size() < 2)
		{
			return String.join("", l);
		}
		return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
	}
}
