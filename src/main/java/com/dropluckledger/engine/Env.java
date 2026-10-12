/*
 * Copyright (c) 2026, Drop Luck Ledger contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.dropluckledger.engine;

import java.util.HashMap;

/**
 * Variables visible to the expressions of the drop data (kill count, boss settings, ...).
 * A variable that was never set is an error when an expression reads it, but reads as "false"
 * when it is only tested for truth (this mirrors the JavaScript engine the maths was written in).
 */
public final class Env
{
	private final HashMap<String, Double> vars = new HashMap<>();

	public void put(String name, double value)
	{
		vars.put(name, value);
	}

	public Double get(String name)
	{
		return vars.get(name);
	}

	public boolean has(String name)
	{
		return vars.containsKey(name);
	}

	/**
	 * True when the variable exists and is neither 0 nor NaN.
	 */
	public boolean truthy(String name)
	{
		Double v = vars.get(name);
		return v != null && v != 0 && !Double.isNaN(v);
	}

	/**
	 * JavaScript truthiness of a number.
	 */
	public static boolean truthy(double v)
	{
		return v != 0 && !Double.isNaN(v);
	}
}
