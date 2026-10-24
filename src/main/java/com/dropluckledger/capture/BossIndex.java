package com.dropluckledger.capture;

import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Param;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knows how the game names things: which boss a kill-count message or a loot event belongs to, and which mode of the boss
 * it was.  Built from the boss data plus a short list of names that differ in game.
 */
public final class BossIndex
{
	/**
	 * A kill-count label the game prints ("Chambers of Xeric Challenge Mode") and what it says about the kill.
	 */
	public static final class KcLabel
	{
		public final String bossId;
		/**
		 * The label as spelled in the boss data; used as the key for the last count seen.
		 */
		public final String label;
		/**
		 * Boss settings the label implies (for example cm=1).
		 */
		public final Map<String, Object> patch;

		KcLabel(String bossId, String label, Map<String, Object> patch)
		{
			this.bossId = bossId;
			this.label = label;
			this.patch = patch;
		}
	}

	/**
	 * Extra kill-count labels: game wording that is not in the boss data (normalised form to boss id).
	 */
	private static final String[][] KC_ALIASES = {
		{"Barrows chest", "barrows"},
		{"Barrows chests", "barrows"},
		{"Lunar Chests", "moons-of-peril"},
		{"Grotesque Guardian", "grotesque-guardians"}, // the singular, as the collection log words it
		{"Fortis Colosseum", "fortis-colosseum"},
		{"Colosseum", "fortis-colosseum"},
	};

	/**
	 * Names that loot events carry for a boss, when they are not the boss name or a kill-count label.
	 */
	private static final String[][] LOOT_ALIASES = {
		{"Dusk", "grotesque-guardians"},
		{"Dawn", "grotesque-guardians"},
		{"Crystalline Hunllef", "the-gauntlet"},
		{"Corrupted Hunllef", "the-corrupted-gauntlet"},
		{"Reward pool (Tempoross)", "tempoross"},
		{"Barrows", "barrows"},
		{"Lunar Chest", "moons-of-peril"},
		{"Fortis Colosseum", "fortis-colosseum"},
		{"Sol Heredit", "fortis-colosseum"},
		{"Vet'ion Reborn", "vetion"},
		{"Hueycoatl body", "the-hueycoatl"},
	};

	private static final String SCROLL_BOX = "Scroll box";
	private static final String CLUE_SCROLL = "Clue scroll";

	private final Data data;
	private final Map<String, KcLabel> kcByNorm = new HashMap<>();
	private final Set<String> kcAmbiguous = new HashSet<>();
	private final Map<String, String> lootByNorm = new HashMap<>();
	private final Set<String> lootAmbiguous = new HashSet<>();
	private final Map<String, List<KcLabel>> labelsOf = new LinkedHashMap<>();
	private final Map<String, List<String>> lootNamesOf = new LinkedHashMap<>();
	private final Map<String, Map<String, String>> itemsOf = new HashMap<>();
	/**
	 * Boss id to the normalised names of the other monsters whose loot is recorded under it without counting a kill.
	 */
	private final Map<String, Set<String>> extraLootOf = new HashMap<>();

	public BossIndex(Data data)
	{
		this.data = data;
		for (Boss b : data.bosses)
		{
			List<KcLabel> labels = new ArrayList<>();
			for (String label : b.kc)
			{
				KcLabel kl = new KcLabel(b.id, label, patchFor(b, label));
				labels.add(kl);
				addKc(Names.norm(label), kl);
			}
			if (labels.isEmpty())
			{
				KcLabel kl = new KcLabel(b.id, b.name, new LinkedHashMap<>());
				labels.add(kl);
				addKc(Names.norm(b.name), kl);
			}
			labelsOf.put(b.id, Collections.unmodifiableList(labels));
			addKc(Names.norm(b.name), labels.get(0)); // the page name: the main kill count
			addKc(Names.norm(stripParens(b.name)), labels.get(0));

			LinkedHashSet<String> names = new LinkedHashSet<>();
			names.add(b.name);
			names.add(stripParens(b.name));
			names.addAll(b.kc);
			lootNamesOf.put(b.id, new ArrayList<>(names));
			for (String n : names)
			{
				addLoot(Names.norm(n), b.id);
			}
		}
		for (String[] a : KC_ALIASES)
		{
			Boss b = data.boss(a[1]);
			if (b != null)
			{
				addKc(Names.norm(a[0]), labelsOf.get(b.id).get(0));
			}
		}
		for (Boss b : data.bosses)
		{
			for (String n : b.extraLoot)
			{
				addLoot(Names.norm(n), b.id);
				extraLootOf.computeIfAbsent(b.id, k -> new HashSet<>()).add(Names.norm(n));
				if (!lootNamesOf.get(b.id).contains(n))
				{
					lootNamesOf.get(b.id).add(n);
				}
			}
		}
		for (String[] a : LOOT_ALIASES)
		{
			if (data.boss(a[1]) != null)
			{
				addLoot(Names.norm(a[0]), a[1]);
				List<String> names = lootNamesOf.get(a[1]);
				if (!names.contains(a[0]))
				{
					names.add(a[0]);
				}
			}
		}
	}

	private void addKc(String norm, KcLabel kl)
	{
		if (norm.isEmpty() || kcAmbiguous.contains(norm))
		{
			return;
		}
		KcLabel old = kcByNorm.get(norm);
		if (old == null)
		{
			kcByNorm.put(norm, kl);
		}
		else if (!old.bossId.equals(kl.bossId))
		{
			kcByNorm.remove(norm);
			kcAmbiguous.add(norm);
		}
	}

	private void addLoot(String norm, String bossId)
	{
		if (norm.isEmpty() || lootAmbiguous.contains(norm))
		{
			return;
		}
		String old = lootByNorm.get(norm);
		if (old == null)
		{
			lootByNorm.put(norm, bossId);
		}
		else if (!old.equals(bossId))
		{
			lootByNorm.remove(norm);
			lootAmbiguous.add(norm);
		}
	}

	private static String stripParens(String s)
	{
		int i = s.indexOf('(');
		return i > 0 ? s.substring(0, i).trim() : s;
	}

	private static Param param(Boss b, String name)
	{
		for (Param p : b.params)
		{
			if (p.name.equals(name))
			{
				return p;
			}
		}
		return null;
	}

	/**
	 * The boss settings a kill-count label implies: awakened or not, challenge mode or not, Theatre of Blood mode.
	 */
	public static Map<String, Object> patchOf(Boss b, String label)
	{
		return patchFor(b, label);
	}

	static Map<String, Object> patchFor(Boss b, String label)
	{
		Map<String, Object> p = new LinkedHashMap<>();
		String l = label.toLowerCase(java.util.Locale.ROOT);
		Param aw = param(b, "awakened");
		if (aw != null && "bool".equals(aw.type))
		{
			p.put("awakened", l.contains("awakened") ? 1 : 0);
		}
		Param cm = param(b, "cm");
		if (cm != null && "bool".equals(cm.type))
		{
			p.put("cm", l.contains("challenge mode") ? 1 : 0);
		}
		Param mode = param(b, "mode");
		if (mode != null && "select".equals(mode.type) && mode.options != null)
		{
			String v = l.contains("hard mode") ? "hard" : l.contains("entry mode") ? "entry" : "normal";
			if (mode.options.contains(v))
			{
				p.put("mode", v);
			}
		}
		return p;
	}

	// ------------------------------------------------------------------ lookups

	/**
	 * The kill-count label for the text the game prints between "Your" and "kill count" (null = not a tracked boss).
	 */
	public KcLabel kc(String gameLabel)
	{
		String n = Names.norm(gameLabel);
		KcLabel kl = kcByNorm.get(n);
		if (kl != null)
		{
			return kl;
		}
		// "Awakened The Leviathan", "The Leviathan (Awakened)" and similar spellings of the awakened versions
		String lower = gameLabel == null ? "" : gameLabel.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("awakened"))
		{
			String rest = Names.norm(lower.replace("awakened", ""));
			KcLabel base = kcByNorm.get(rest);
			if (base != null && param(data.boss(base.bossId), "awakened") != null)
			{
				Map<String, Object> patch = new LinkedHashMap<>(base.patch);
				patch.put("awakened", 1);
				return new KcLabel(base.bossId, "Awakened " + base.label, patch);
			}
		}
		return null;
	}

	/**
	 * The words the game's screens put after the activity in a count ("Zulrah kills", "Barrows Chests opened", "Chambers of
	 * Xeric completions"), longest first.
	 */
	private static final String[] SHOWN_SUFFIXES = {" kill count", " completion count", " kills", " kill", " completions",
		" completion", " completed", " opened", " count"};

	/**
	 * The kill-count label for a count the game shows on a screen: a row of the Boss Kill Log ("Chambers of Xeric: Challenge
	 * Mode") or a line of a collection log page ("Zulrah kills", "Chambers of Xeric (CM) completions").
	 *
	 * @return null when it is not a count of a tracked boss
	 */
	public KcLabel kcShown(String shown)
	{
		if (shown == null)
		{
			return null;
		}
		String s = shown.trim();
		KcLabel kl = kc(s);
		if (kl != null)
		{
			return kl;
		}
		String lower = s.toLowerCase(java.util.Locale.ROOT);
		for (String suffix : SHOWN_SUFFIXES)
		{
			if (lower.endsWith(suffix) && lower.length() > suffix.length())
			{
				s = s.substring(0, s.length() - suffix.length()).trim();
				break;
			}
		}
		// short forms of the modes: "(CM)", "(HM)", "(Entry)"
		s = s.replaceAll("(?i)\\(\\s*cm\\s*\\)", "Challenge Mode")
			.replaceAll("(?i)\\(\\s*hm\\s*\\)", "Hard Mode")
			.replaceAll("(?i)\\(\\s*(hard|entry|expert)\\s*\\)", "$1 Mode");
		return kc(s);
	}

	/**
	 * The boss a loot event name belongs to (null = not a boss we track).
	 */
	public String loot(String lootName)
	{
		return lootByNorm.get(Names.norm(lootName));
	}

	/**
	 * True when a loot event of this name is the loot of another monster that counts for the boss (a God Wars bodyguard): it is
	 * recorded, but it is not a kill of the boss.
	 */
	public boolean isExtraLoot(String bossId, String lootName)
	{
		Set<String> s = extraLootOf.get(bossId);
		return s != null && s.contains(Names.norm(lootName));
	}

	/**
	 * Every kill-count label of a boss, in the order of the boss data (the first one is the main mode).
	 */
	public List<KcLabel> labels(String bossId)
	{
		List<KcLabel> l = labelsOf.get(bossId);
		return l == null ? Collections.emptyList() : l;
	}

	/**
	 * Normalised names that two bosses both claim (the lookups return nothing for them).
	 */
	public Set<String> ambiguousKc()
	{
		return Collections.unmodifiableSet(kcAmbiguous);
	}

	public Set<String> ambiguousLoot()
	{
		return Collections.unmodifiableSet(lootAmbiguous);
	}

	/**
	 * The boss's own name for an item the game names (exact match first, then ignoring case and punctuation).  Null when
	 * the boss has no such item.
	 */
	public String item(String bossId, String gameName)
	{
		if (gameName == null)
		{
			return null;
		}
		Boss b = data.boss(bossId);
		if (b == null)
		{
			return null;
		}
		if (b.meta().containsKey(gameName))
		{
			return gameName;
		}
		Map<String, String> flat;
		synchronized (itemsOf)
		{
			flat = itemsOf.get(bossId);
			if (flat == null)
			{
				flat = new HashMap<>();
				for (String n : b.meta().keySet())
				{
					flat.putIfAbsent(Names.flat(n), n);
				}
				// the wiki names a few items differently from the game ("Tooth half of key (moon key)" is "Tooth half of key")
				for (Data.Row r : b.allRows())
				{
					String game = r.attr("game");
					if (game != null && !game.isEmpty())
					{
						flat.putIfAbsent(Names.flat(game), r.name);
					}
				}
				itemsOf.put(bossId, flat);
			}
		}
		String found = flat.get(Names.flat(gameName));
		if (found == null && gameName.startsWith(SCROLL_BOX))
		{
			// since July 2025 monsters drop a scroll box in place of a clue scroll (once X Marks the Spot is done)
			found = flat.get(Names.flat(CLUE_SCROLL + gameName.substring(SCROLL_BOX.length())));
		}
		return found;
	}
}
