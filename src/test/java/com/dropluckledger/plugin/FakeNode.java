package com.dropluckledger.plugin;

import java.util.Arrays;
import java.util.List;

/**
 * A piece of the game's interface with the fields the readers look at.
 */
public final class FakeNode implements Game.Node
{
	public String text = "";
	public int opacity;
	public int itemId = -1;
	public int itemQuantity;
	public List<Game.Node> children;

	public static FakeNode text(String text)
	{
		FakeNode n = new FakeNode();
		n.text = text;
		return n;
	}

	public static FakeNode item(int id, int qty, boolean owned)
	{
		FakeNode n = new FakeNode();
		n.itemId = id;
		n.itemQuantity = qty;
		n.opacity = owned ? 0 : 175;
		return n;
	}

	public static FakeNode withChildren(Game.Node... children)
	{
		FakeNode n = new FakeNode();
		n.children = Arrays.asList(children);
		return n;
	}

	@Override
	public String text()
	{
		return text;
	}

	@Override
	public List<Game.Node> children()
	{
		return children;
	}

	@Override
	public Game.Node child(int index)
	{
		return children == null || index < 0 || index >= children.size() ? null : children.get(index);
	}

	@Override
	public int itemId()
	{
		return itemId;
	}

	@Override
	public int itemQuantity()
	{
		return itemQuantity;
	}

	@Override
	public int opacity()
	{
		return opacity;
	}
}
