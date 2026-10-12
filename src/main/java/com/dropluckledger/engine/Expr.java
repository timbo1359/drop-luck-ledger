package com.dropluckledger.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small expression language used by the drop data ("1/181.1", "if(kc>200, 1/50, 1/100)", ...).
 * Port of parseExpr() from the web app's engine; the semantics are identical (numbers only,
 * 0/NaN are false, comparison with a 1e-9 tolerance for == and !=).
 */
public final class Expr
{
	/**
	 * A compiled node.
	 */
	@FunctionalInterface
	public interface Node
	{
		double eval(Env env);
	}

	private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

	private final Node fn;
	private final Set<String> idents;
	private final String src;

	private Expr(Node fn, Set<String> idents, String src)
	{
		this.fn = fn;
		this.idents = idents;
		this.src = src;
	}

	public double eval(Env env)
	{
		return fn.eval(env);
	}

	/**
	 * Names of the variables the expression reads.
	 */
	public Set<String> idents()
	{
		return idents;
	}

	/**
	 * The source text ("" for compound conditions).
	 */
	public String src()
	{
		return src;
	}

	public static boolean truthy(double v)
	{
		return v != 0 && !Double.isNaN(v);
	}

	/**
	 * An expression that is the same number everywhere (the word "always").
	 */
	public static Expr constant(double v)
	{
		return new Expr(env -> v, new HashSet<>(), "");
	}

	// ------------------------------------------------------------------ tokenizer
	private static final class Tok
	{
		final char kind; // 'n' number, 'i' identifier, 'o' operator
		final String text;
		final double num;

		Tok(char kind, String text, double num)
		{
			this.kind = kind;
			this.text = text;
			this.num = num;
		}
	}

	/**
	 * JavaScript's parseFloat on a string made of digits and dots: the longest valid prefix.
	 */
	static double parseFloat(String s)
	{
		int i = 0;
		int n = s.length();
		while (i < n && Character.isDigit(s.charAt(i)))
		{
			i++;
		}
		int end = i;
		if (i < n && s.charAt(i) == '.')
		{
			i++;
			while (i < n && Character.isDigit(s.charAt(i)))
			{
				i++;
			}
			end = i;
		}
		String t = s.substring(0, end);
		if (t.isEmpty() || t.equals("."))
		{
			return Double.NaN;
		}
		return Double.parseDouble(t);
	}

	private static List<Tok> tokenize(String s)
	{
		List<Tok> t = new ArrayList<>();
		int i = 0;
		int n = s.length();
		while (i < n)
		{
			char c = s.charAt(i);
			if (Character.isWhitespace(c))
			{
				i++;
				continue;
			}
			if ((c >= '0' && c <= '9') || c == '.')
			{
				int j = i;
				while (j < n && ((s.charAt(j) >= '0' && s.charAt(j) <= '9') || s.charAt(j) == '.'))
				{
					j++;
				}
				t.add(new Tok('n', s.substring(i, j), parseFloat(s.substring(i, j))));
				i = j;
				continue;
			}
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_')
			{
				int j = i;
				while (j < n && ((s.charAt(j) >= 'A' && s.charAt(j) <= 'Z') || (s.charAt(j) >= 'a' && s.charAt(j) <= 'z')
					|| (s.charAt(j) >= '0' && s.charAt(j) <= '9') || s.charAt(j) == '_'))
				{
					j++;
				}
				t.add(new Tok('i', s.substring(i, j), 0));
				i = j;
				continue;
			}
			if (i + 1 < n)
			{
				String two = s.substring(i, i + 2);
				if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=") || two.equals("&&") || two.equals("||"))
				{
					t.add(new Tok('o', two, 0));
					i += 2;
					continue;
				}
			}
			if ("+-*/(),<>!".indexOf(c) >= 0)
			{
				t.add(new Tok('o', String.valueOf(c), 0));
				i++;
				continue;
			}
			throw new IllegalArgumentException("bad character \"" + c + "\" in expression: " + s);
		}
		return t;
	}

	// ------------------------------------------------------------------ functions
	private static double call(String name, double[] a, String src)
	{
		switch (name)
		{
			case "min":
			{
				double r = Double.POSITIVE_INFINITY;
				for (double x : a)
				{
					r = Math.min(r, x);
				}
				return r;
			}
			case "max":
			{
				double r = Double.NEGATIVE_INFINITY;
				for (double x : a)
				{
					r = Math.max(r, x);
				}
				return r;
			}
			case "clamp":
				return Math.min(Math.max(arg(a, 0), arg(a, 1)), arg(a, 2));
			case "floor":
				return Math.floor(arg(a, 0));
			case "ceil":
				return Math.ceil(arg(a, 0));
			case "round":
				return Math.floor(arg(a, 0) + 0.5);
			case "abs":
				return Math.abs(arg(a, 0));
			case "pow":
				return Math.pow(arg(a, 0), arg(a, 1));
			case "if":
				return truthy(arg(a, 0)) ? arg(a, 1) : arg(a, 2);
			case "interp":
			{
				// interp(x, x1,y1, x2,y2, ...) piecewise linear
				int np = a.length - 1;
				if (np < 4 || np % 2 != 0)
				{
					throw new IllegalArgumentException("interp needs x1,y1,x2,y2,...");
				}
				double x = a[0];
				if (x <= a[1])
				{
					return a[2];
				}
				for (int i = 3; i < a.length; i += 2)
				{
					if (x <= a[i])
					{
						double x0 = a[i - 2], y0 = a[i - 1], x1 = a[i], y1 = a[i + 1];
						return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
					}
				}
				return a[a.length - 1];
			}
			case "step":
			{
				double x = a[0];
				double r = 0;
				for (int i = 1; i + 1 < a.length; i += 2)
				{
					if (x >= a[i])
					{
						r = a[i + 1];
					}
				}
				return r;
			}
			default:
				throw new IllegalArgumentException("unknown function " + name + " in " + src);
		}
	}

	private static double arg(double[] a, int i)
	{
		return i < a.length ? a[i] : Double.NaN; // a missing argument is undefined in the original
	}

	private static boolean knownFunction(String name)
	{
		switch (name)
		{
			case "min":
			case "max":
			case "clamp":
			case "floor":
			case "ceil":
			case "round":
			case "abs":
			case "pow":
			case "if":
			case "interp":
			case "step":
				return true;
			default:
				return false;
		}
	}

	// ------------------------------------------------------------------ parser
	/**
	 * Parses (and caches) an expression.
	 */
	public static Expr parse(String src)
	{
		Expr hit = CACHE.get(src);
		if (hit != null)
		{
			return hit;
		}
		Expr e = new Parser(src).run();
		CACHE.put(src, e);
		return e;
	}

	/**
	 * A condition list "a,b" meaning a AND b (each part is an expression), as used by the if= attribute.
	 */
	public static Expr cond(String src)
	{
		List<String> parts = splitTop(src, ',');
		List<Expr> es = new ArrayList<>();
		Set<String> ids = new LinkedHashSet<>();
		for (String p : parts)
		{
			Expr e = parse(p);
			es.add(e);
			ids.addAll(e.idents);
		}
		return new Expr(env ->
		{
			for (Expr p : es)
			{
				if (!truthy(p.eval(env)))
				{
					return 0;
				}
			}
			return 1;
		}, ids, "");
	}

	/**
	 * A rate such as "1/181.1", "3.5/25", "~0.02" or "Always".
	 */
	public static Expr rate(String s)
	{
		String t = s.trim().replace(",", "");
		if (t.startsWith("~"))
		{
			t = t.substring(1);
		}
		if (t.equalsIgnoreCase("always"))
		{
			return constant(1);
		}
		return parse(t);
	}

	/**
	 * Splits at top-level separators (not inside parentheses), trimmed, empties dropped.
	 */
	public static List<String> splitTop(String s, char sep)
	{
		List<String> out = new ArrayList<>();
		int d = 0;
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c == '(')
			{
				d++;
			}
			if (c == ')')
			{
				d--;
			}
			if (c == sep && d == 0)
			{
				out.add(cur.toString());
				cur.setLength(0);
			}
			else
			{
				cur.append(c);
			}
		}
		out.add(cur.toString());
		List<String> res = new ArrayList<>();
		for (String x : out)
		{
			String tr = x.trim();
			if (!tr.isEmpty())
			{
				res.add(tr);
			}
		}
		return res;
	}

	private static final class Parser
	{
		private final String src;
		private final List<Tok> tk;
		private int pos;
		private final Set<String> idents = new LinkedHashSet<>();

		Parser(String src)
		{
			this.src = src;
			this.tk = tokenize(src);
		}

		Expr run()
		{
			Node n = orE();
			if (pos < tk.size())
			{
				throw new IllegalArgumentException("trailing tokens in expression: " + src);
			}
			return new Expr(n, idents, src);
		}

		private Tok peek()
		{
			return pos < tk.size() ? tk.get(pos) : null;
		}

		private Tok eat()
		{
			return pos < tk.size() ? tk.get(pos++) : null;
		}

		private boolean isOp(String v)
		{
			Tok p = peek();
			return p != null && p.kind == 'o' && p.text.equals(v);
		}

		private Node primary()
		{
			Tok x = eat();
			if (x == null)
			{
				throw new IllegalArgumentException("unexpected end of expression: " + src);
			}
			if (x.kind == 'n')
			{
				final double v = x.num;
				return env -> v;
			}
			if (x.kind == 'i')
			{
				if (isOp("("))
				{
					eat();
					List<Node> args = new ArrayList<>();
					if (!isOp(")"))
					{
						for (;;)
						{
							args.add(orE());
							if (isOp(","))
							{
								eat();
								continue;
							}
							break;
						}
					}
					if (!isOp(")"))
					{
						throw new IllegalArgumentException("missing ) in " + src);
					}
					eat();
					final String fname = x.text;
					if (!knownFunction(fname))
					{
						throw new IllegalArgumentException("unknown function " + fname + " in " + src);
					}
					final Node[] an = args.toArray(new Node[0]);
					final String s = src;
					return env ->
					{
						double[] a = new double[an.length];
						for (int i = 0; i < an.length; i++)
						{
							a[i] = an[i].eval(env); // all arguments are evaluated, as in the original
						}
						return call(fname, a, s);
					};
				}
				final String name = x.text;
				idents.add(name);
				final String s = src;
				return env ->
				{
					Double v = env.get(name);
					if (v == null)
					{
						throw new IllegalStateException("undefined variable \"" + name + "\" in expression: " + s);
					}
					return v;
				};
			}
			if (x.kind == 'o' && x.text.equals("("))
			{
				Node e = orE();
				if (!isOp(")"))
				{
					throw new IllegalArgumentException("missing ) in " + src);
				}
				eat();
				return e;
			}
			throw new IllegalArgumentException("unexpected token " + x.text + " in " + src);
		}

		private Node unary()
		{
			if (isOp("-"))
			{
				eat();
				final Node e = unary();
				return env -> -e.eval(env);
			}
			if (isOp("!"))
			{
				eat();
				final Node e = unary();
				return env -> truthy(e.eval(env)) ? 0 : 1;
			}
			return primary();
		}

		private Node mul()
		{
			Node l = unary();
			while (isOp("*") || isOp("/"))
			{
				final String o = eat().text;
				final Node r = unary();
				final Node a = l;
				l = o.equals("*") ? env -> a.eval(env) * r.eval(env) : env -> a.eval(env) / r.eval(env);
			}
			return l;
		}

		private Node add()
		{
			Node l = mul();
			while (isOp("+") || isOp("-"))
			{
				final String o = eat().text;
				final Node r = mul();
				final Node a = l;
				l = o.equals("+") ? env -> a.eval(env) + r.eval(env) : env -> a.eval(env) - r.eval(env);
			}
			return l;
		}

		private Node cmp()
		{
			Node l = add();
			for (;;)
			{
				Tok p = peek();
				if (p == null || p.kind != 'o')
				{
					break;
				}
				String o = p.text;
				if (!(o.equals("<") || o.equals(">") || o.equals("<=") || o.equals(">=") || o.equals("==") || o.equals("!=")))
				{
					break;
				}
				eat();
				final Node r = add();
				final Node a = l;
				final String op = o;
				l = env ->
				{
					double x = a.eval(env);
					double y = r.eval(env);
					boolean res;
					switch (op)
					{
						case "<":
							res = x < y;
							break;
						case ">":
							res = x > y;
							break;
						case "<=":
							res = x <= y;
							break;
						case ">=":
							res = x >= y;
							break;
						case "==":
							res = Math.abs(x - y) < 1e-9;
							break;
						default:
							res = Math.abs(x - y) >= 1e-9;
							break;
					}
					return res ? 1 : 0;
				};
			}
			return l;
		}

		private Node andE()
		{
			Node l = cmp();
			while (isOp("&&"))
			{
				eat();
				final Node r = cmp();
				final Node a = l;
				l = env -> (truthy(a.eval(env)) && truthy(r.eval(env))) ? 1 : 0;
			}
			return l;
		}

		private Node orE()
		{
			Node l = andE();
			while (isOp("||"))
			{
				eat();
				final Node r = andE();
				final Node a = l;
				l = env -> (truthy(a.eval(env)) || truthy(r.eval(env))) ? 1 : 0;
			}
			return l;
		}
	}
}
