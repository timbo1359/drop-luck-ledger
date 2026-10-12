package com.dropluckledger.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boss settings (task, team size, mode, ...) that apply from a kill number on.
 */
public final class Period
{
	public int from = 1;
	public Map<String, Object> params = new LinkedHashMap<>();

	public Period()
	{
	}

	public Period(int from, Map<String, Object> params)
	{
		this.from = from;
		this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
	}

	public Period copy()
	{
		return new Period(from, params);
	}
}
