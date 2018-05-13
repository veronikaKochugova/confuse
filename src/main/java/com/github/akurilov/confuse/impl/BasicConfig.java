package com.github.akurilov.confuse.impl;

import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.exceptions.InvalidValuePathException;
import com.github.akurilov.confuse.exceptions.InvalidValueTypeException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class BasicConfig
implements Config {

	private final String pathSep;
	private final Map<String, Object> schema;
	private final Map<String, Object> node;

	public BasicConfig(final String pathSep, final Map<String, Object> schema)
	throws IllegalArgumentException {
		this(pathSep, schema, Collections.emptyMap());
	}

	@SuppressWarnings("unchecked")
	public BasicConfig(
		final String pathSep, final Map<String, Object> schema, final Map<String, Object> node
	) throws InvalidValuePathException, InvalidValueTypeException, IllegalArgumentException {
		if(pathSep == null || pathSep.isEmpty()) {
			throw new IllegalArgumentException("Path separator should not be null/empty");
		}
		this.pathSep = pathSep;
		this.schema = deepCopyMap(schema);
		if(node == null || node.size() == 0) {
			this.node = new HashMap<>();
		} else {
			this.node = new HashMap<>(node.size());
			node.forEach(this::putBranch);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> deepCopyMap(final Map<String, Object> src) {
		final Map<String, Object> dst = new HashMap<>(src.size());
		src.forEach(
			(k, v) -> dst.put(k, v instanceof Class ? v : deepCopyMap((Map<String, Object>) v))
		);
		return dst;
	}

	@SuppressWarnings("unchecked")
	private void putBranch(final String key, final Object val)
	throws InvalidValuePathException, InvalidValueTypeException, IllegalArgumentException {
		final Object schemaVal = schema.get(key);
		if(schemaVal instanceof Map) {
			putBranch(key, (Map<String, Object>) schemaVal, val);
		} else if(schemaVal instanceof Class){
			putLeaf(key, (Class) schemaVal, val);
		} else {
			throw new IllegalArgumentException("Schema contains invalid value: " + schemaVal);
		}
	}

	@SuppressWarnings("unchecked")
	private void putBranch(
		final String key, final Map<String, Object> schemaBranch, final Object val
	) throws InvalidValuePathException, InvalidValueTypeException {
		final Config branch;
		if(val instanceof Config) {
			branch = new BasicConfig((Config) val);
		} else if(val instanceof Map) {
			branch = new BasicConfig(pathSep, schemaBranch, (Map<String, Object>) val);
		} else {
			throw new InvalidValuePathException(key);
		}
		node.put(key, branch);
	}

	protected void putLeaf(final String key, final Class expectedValType, final Object val)
	throws InvalidValuePathException, InvalidValueTypeException {
		if(expectedValType.isPrimitive()) {
			if(val == null) {
				throw new InvalidValueTypeException(key, expectedValType, null);
			}
			final Class actualValType = val.getClass();
			if(actualValType.isPrimitive()) {
				if(!expectedValType.equals(actualValType)) {
					throw new InvalidValueTypeException(key, expectedValType, actualValType);
				}
			} else {
				try {
					if(!expectedValType.equals(actualValType.getField("TYPE").get(null))) {
						throw new InvalidValueTypeException(key, expectedValType, actualValType);
					}
				} catch(final NoSuchFieldException e) {
					throw new InvalidValueTypeException(key, expectedValType, actualValType);
				} catch(final IllegalAccessException e) {
					throw new AssertionError(e);
				}
			}
			node.put(key, val);
		} else {
			try {
				node.put(key, expectedValType.cast(val));
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(key, expectedValType, val.getClass());
			}
		}
	}

	/**
	 Cloning constructor
	 @param other the config instance to clone
	 */
	public BasicConfig(final Config other)
	throws InvalidValuePathException, InvalidValueTypeException, IllegalArgumentException {
		this(other.pathSep(), other.schema(), other.mapVal(ROOT_PATH));
	}

	@Override
	public final String pathSep() {
		return pathSep;
	}

	@Override
	public final Map<String, Object> schema() {
		return schema;
	}

	@Override
	public Object val(final String path)
	throws InvalidValuePathException, NoSuchElementException {
		if(path == null || path.isEmpty()) {
			return node;
		}
		final int sepPos = path.indexOf(pathSep);
		if(sepPos == 0 || sepPos == path.length() - 1) {
			throw new InvalidValuePathException(path);
		}
		if(sepPos > 0) {
			final String key = path.substring(0, sepPos);
			final String subPath = path.substring(sepPos + 1);
			final Object child = node.get(key);
			if(child instanceof Config) {
				try {
					return ((Config) child).val(subPath);
				} catch(final InvalidValuePathException e) {
					throw new InvalidValuePathException(key + pathSep + e.path());
				} catch(final NoSuchElementException e) {
					throw new NoSuchElementException(key + pathSep + e.getMessage());
				}
			} else {
				throw new NoSuchElementException(path);
			}
		} else {
			return node.get(path);
		}
	}

	@Override
	public void val(final String path, final Object val)
	throws InvalidValuePathException, InvalidValueTypeException {
		final int sepPos = path.indexOf(pathSep);
		if(sepPos == 0 || sepPos == path.length() - 1) {
			throw new InvalidValuePathException(path);
		}
		if(sepPos > 0) {
			final String key = path.substring(0, sepPos);
			final String childPath = path.substring(sepPos + 1);
			val(key, childPath, val);
		} else {
			leafVal(path, val);
		}
	}

	@SuppressWarnings("unchecked")
	protected void val(final String key, final String childPath, final Object val)
	throws InvalidValuePathException, InvalidValueTypeException {
		final Object schemaVal = schema.get(key);
		if(schemaVal instanceof Map) {
			final Object child = node.get(key);
			final Config childConfig;
			try {
				if(child instanceof Config) {
					childConfig = (Config) child;
				} else {
					childConfig = new BasicConfig(
						pathSep, (Map<String, Object>) schemaVal
					);
					node.put(key, childConfig);
				}
				childConfig.val(childPath, val);
			} catch(final InvalidValuePathException e) {
				throw new InvalidValuePathException(key + pathSep + e.path());
			} catch(final InvalidValueTypeException e) {
				throw new InvalidValueTypeException(
					key + pathSep + e.path(), e.expectedType(), e.actualType()
				);
			}
		} else {
			throw new InvalidValuePathException(key);
		}
	}

	protected void leafVal(final String key, final Object val)
	throws InvalidValuePathException, InvalidValueTypeException {
		final Object schemaVal = schema.get(key);
		if(schemaVal instanceof Class) {
			putLeaf(key, (Class) schemaVal, val);
		} else {
			throw new InvalidValuePathException(key);
		}
	}

	@Override
	public String stringVal(final String path)
	throws InvalidValuePathException, NoSuchElementException {
		return val(path).toString();
	}

	@Override
	public boolean boolVal(final String path)
	throws InvalidValuePathException, InvalidValueTypeException, NoSuchElementException {
		final Object v = val(path);
		if(v instanceof String) {
			return Boolean.parseBoolean((String) v);
		} else {
			try {
				return (boolean) v;
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(path, Boolean.TYPE, v.getClass());
			}
		}
	}

	@Override
	public int intVal(final String path)
	throws InvalidValuePathException, NoSuchElementException, NumberFormatException {
		final Object v = val(path);
		if(v instanceof String) {
			return Integer.parseInt((String) v);
		} else {
			try {
				return (int) v;
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(path, Integer.TYPE, v.getClass());
			}
		}
	}

	@Override
	public long longVal(final String path)
	throws InvalidValuePathException, NoSuchElementException, NumberFormatException {
		final Object v = val(path);
		if(v instanceof String) {
			return Long.parseLong((String) v);
		} else if(v instanceof Short) {
			return (short) v;
		} else if(v instanceof Integer) {
			return (int) v;
		} else {
			try {
				return (long) v;
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(path, Long.TYPE, v.getClass());
			}
		}
	}

	@Override
	public double doubleVal(final String path)
	throws InvalidValuePathException, NoSuchElementException, NumberFormatException {
		final Object v = val(path);
		if(v instanceof String) {
			return Double.parseDouble((String) v);
		} else if(v instanceof Float) {
			return (float) v;
		} else {
			try {
				return (double) v;
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(path, Double.TYPE, v.getClass());
			}
		}
	}

	@Override @SuppressWarnings("unchecked")
	public <V> Map<String, V> mapVal(final String path)
	throws InvalidValuePathException, NoSuchElementException {
		final Object v = val(path);
		if(v instanceof Config) {
			return ((Config) v).mapVal(ROOT_PATH);
		} else {
			try {
				return (Map<String, V>) v;
			} catch(final ClassCastException e) {
				throw new InvalidValueTypeException(path, Map.class, v.getClass());
			}
		}
	}

	@Override @SuppressWarnings("unchecked")
	public <E> List<E> listVal(final String path)
	throws InvalidValuePathException, NoSuchElementException {
		final Object v = val(path);
		try {
			return (List<E>) v;
		} catch(final ClassCastException e) {
			throw new InvalidValueTypeException(path, List.class, v.getClass());
		}
	}

	@Override
	public boolean equals(final Object o) {
		if(o == null) {
			return false;
		}
		if(!(o instanceof BasicConfig)) {
			return false;
		}
		final BasicConfig other = (BasicConfig) o;
		for(final String key : node.keySet()) {
			final Object val = node.get(key);
			if(val == null) {
				if(other.node.get(key) != null) {
					return false;
				}
			} else if(!val.equals(other.node.get(key))) {
				return false;
			}
		}
		return true;
	}
}