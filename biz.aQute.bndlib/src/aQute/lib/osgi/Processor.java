package aQute.lib.osgi;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.regex.*;

import aQute.bnd.make.*;
import aQute.bnd.make.component.*;
import aQute.bnd.make.metatype.*;
import aQute.bnd.maven.*;
import aQute.bnd.service.*;
import aQute.libg.generics.*;
import aQute.libg.header.*;
import aQute.libg.reporter.*;

public class Processor implements Reporter, Constants, Closeable {
	// TODO handle include files out of date
	public static String	DEFAULT_PLUGINS	= "";								// "aQute.lib.spring.SpringComponent";
	// TODO make splitter skip eagerly whitespace so trim is not necessary
	public static String	LIST_SPLITTER	= "\\\\?\\s*,\\s*";
	static Executor			executor;
	private List<String>	errors			= new ArrayList<String>();
	private List<String>	warnings		= new ArrayList<String>();
	boolean					pedantic;
	boolean					trace;
	boolean					exceptions;
	boolean					fileMustExist	= true;

	List<Object>			plugins;
	private File			base			= new File("").getAbsoluteFile();
	private List<Closeable>	toBeClosed		= newList();

	Properties				properties;
	private Macro			replacer;
	private long			lastModified;
	private File			propertiesFile;
	private boolean			fixup			= true;
	long					modified;
	Processor				parent;
	Set<File>				included;
	CL						pluginLoader;
	Collection<String>		filter;
	HashSet<String>			missingCommand;
	List<Object>			basicPlugins	= new ArrayList<Object>();

	public Processor() {
		properties = new Properties();
	}

	public Processor(Properties parent) {
		properties = new Properties(parent);
	}

	public Processor(Processor parent) {
		this(parent.properties);
		this.parent = parent;
	}

	public void setParent(Processor processor) {
		this.parent = processor;
		Properties ext = new Properties(processor.properties);
		ext.putAll(this.properties);
		this.properties = ext;
	}

	public Processor getParent() {
		return parent;
	}

	public Processor getTop() {
		if (parent == null)
			return this;
		else
			return parent.getTop();
	}

	public void getInfo(Processor processor, String prefix) {
		if (isFailOk())
			addAll(warnings, processor.getErrors(), prefix);
		else
			addAll(errors, processor.getErrors(), prefix);
		addAll(warnings, processor.getWarnings(), prefix);

		processor.errors.clear();
		processor.warnings.clear();
	}

	public void getInfo(Processor processor) {
		getInfo(processor, "");
	}

	private <T> void addAll(List<String> to, List<? extends T> from, String prefix) {
		for (T x : from) {
			to.add(prefix + x);
		}
	}

	public void warning(String string, Object... args) {
		String s = String.format(string, args);
		if (!warnings.contains(s))
			warnings.add(s);
	}

	public void error(String string, Object... args) {
		if (isFailOk())
			warning(string, args);
		else {
			String s = String.format(string, args);
			if (!errors.contains(s))
				errors.add(s);
		}
	}

	public void error(String string, Throwable t, Object... args) {
		if (isFailOk())
			warning(string + ": " + t, args);
		else {
			errors.add("Exception: " + t.getMessage());
			String s = String.format(string, args);
			if (!errors.contains(s))
				errors.add(s);
		}
		if (exceptions)
			t.printStackTrace();
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public List<String> getErrors() {
		return errors;
	}

	public Map<String, Map<String, String>> parseHeader(String value) {
		return parseHeader(value, this);
	}

	/**
	 * Standard OSGi header parser.
	 * 
	 * @param value
	 * @return
	 */
	static public Map<String, Map<String, String>> parseHeader(String value, Processor logger) {
		return OSGiHeader.parseHeader(value, logger);
	}

	Map<String, Map<String, String>> getClauses(String header) {
		return parseHeader(getProperty(header));
	}

	public void addClose(Closeable jar) {
		toBeClosed.add(jar);
	}

	/**
	 * Remove all entries from a map that start with a specific prefix
	 * 
	 * @param <T>
	 * @param source
	 * @param prefix
	 * @return
	 */
	static <T> Map<String, T> removeKeys(Map<String, T> source, String prefix) {
		Map<String, T> temp = new TreeMap<String, T>(source);
		for (Iterator<String> p = temp.keySet().iterator(); p.hasNext();) {
			String pack = (String) p.next();
			if (pack.startsWith(prefix))
				p.remove();
		}
		return temp;
	}

	public void progress(String s, Object... args) {
		// System.out.println(s);
	}

	public boolean isPedantic() {
		return pedantic;
	}

	public void setPedantic(boolean pedantic) { // System.out.println("Set
		// pedantic: " + pedantic + " "
		// + this );
		this.pedantic = pedantic;
	}

	public static File getFile(File base, String file) {
		File f = new File(file);
		if (f.isAbsolute())
			return f;
		int n;

		f = base.getAbsoluteFile();
		while ((n = file.indexOf('/')) > 0) {
			String first = file.substring(0, n);
			file = file.substring(n + 1);
			if (first.equals(".."))
				f = f.getParentFile();
			else
				f = new File(f, first);
		}
		if (file.equals(".."))
			return f.getParentFile();
		else
			return new File(f, file).getAbsoluteFile();
	}

	public File getFile(String file) {
		return getFile(base, file);
	}

	/**
	 * Return a list of plugins that implement the given class.
	 * 
	 * @param clazz
	 *            Each returned plugin implements this class/interface
	 * @return A list of plugins
	 */
	public <T> List<T> getPlugins(Class<T> clazz) {
		List<T> l = new ArrayList<T>();
		List<Object> all = getPlugins();
		for (Object plugin : all) {
			if (clazz.isInstance(plugin))
				l.add(clazz.cast(plugin));
		}
		return l;
	}

	/**
	 * Return a list of plugins. Plugins are defined with the -plugin command.
	 * They are class names, optionally associated with attributes. Plugins can
	 * implement the Plugin interface to see these attributes.
	 * 
	 * Any object can be a plugin.
	 * 
	 * @return
	 */
	protected synchronized List<Object> getPlugins() {
		// if (parent != null) {
		// List<Object> val = parent.getPlugins();
		// getInfo(parent);
		// return val;
		// }
		if (this.plugins != null)
			return this.plugins;

		missingCommand = new HashSet<String>();
		String spe = getProperty(Analyzer.PLUGIN, DEFAULT_PLUGINS);
		Map<String, Map<String, String>> plugins = parseHeader(spe);
		List<Object> list = new ArrayList<Object>();

		// Add the default plugins. Only if non is specified
		// will they be removed.
		list.add(new MakeBnd());
		list.add(new MakeCopy());
		list.add(new ServiceComponent());
		list.add(new MetatypePlugin());
		list.addAll(basicPlugins);

		for (Map.Entry<String, Map<String, String>> entry : plugins.entrySet()) {
			String key = (String) entry.getKey();
			if (key.equals(NONE))
				return this.plugins = newList();

			try {
				CL loader = getLoader();
				String path = entry.getValue().get(PATH_DIRECTIVE);
				if (path != null) {
					String parts[] = path.split("\\s*,\\s*");
					for (String p : parts) {
						File f = getFile(p).getAbsoluteFile();
						loader.add(f.toURI().toURL());
					}
				}

				trace("Using plugin %s", key);

				// Plugins could use the same class with different
				// parameters so we could have duplicate names Remove
				// the ! added by the parser to make each name unique.
				key = removeDuplicateMarker(key);

				try {
					Class<?> c = (Class<?>) loader.loadClass(key);
					Object plugin = c.newInstance();
					if (plugin instanceof Plugin) {
						((Plugin) plugin).setProperties(entry.getValue());
						((Plugin) plugin).setReporter(this);
					}
					list.add(plugin);
				} catch (Throwable t) {
					// We can defer the error if the plugin specifies
					// a command name. In that case, we'll verify that
					// a bnd file does not contain any references to a plugin
					// command. The reason this feature was added was
					// to compile plugin classes with the same build.
					String commands = entry.getValue().get(COMMAND_DIRECTIVE);
					if (commands == null)
						error("Problem loading the plugin: %s exception: (%s)", key, t);
					else {
						Collection<String> cs = split(commands);
						missingCommand.addAll(cs);
					}
				}
			} catch (Throwable e) {
				error("Problem loading the plugin: " + key + " exception: " + e);
			}

		}
		return this.plugins = list;
	}

	public boolean isFailOk() {
		String v = getProperty(Analyzer.FAIL_OK, null);
		return v != null && v.equalsIgnoreCase("true");
	}

	public File getBase() {
		return base;
	}

	public void setBase(File base) {
		this.base = base;
	}

	public void clear() {
		errors.clear();
		warnings.clear();
	}

	public void trace(String msg, Object... parms) {
		if (trace) {
			System.out.printf("# " + msg + "\n", parms);
		}
	}

	public <T> List<T> newList() {
		return new ArrayList<T>();
	}

	public <T> Set<T> newSet() {
		return new TreeSet<T>();
	}

	public static <K, V> Map<K, V> newMap() {
		return new LinkedHashMap<K, V>();
	}

	public static <K, V> Map<K, V> newHashMap() {
		return new HashMap<K, V>();
	}

	public <T> List<T> newList(Collection<T> t) {
		return new ArrayList<T>(t);
	}

	public <T> Set<T> newSet(Collection<T> t) {
		return new TreeSet<T>(t);
	}

	public <K, V> Map<K, V> newMap(Map<K, V> t) {
		return new LinkedHashMap<K, V>(t);
	}

	public void close() {
		for (Closeable c : toBeClosed) {
			try {
				c.close();
			} catch (IOException e) {
				// Who cares?
			}
		}
		toBeClosed = null;
	}

	public String _basedir(String args[]) {
		if (base == null)
			throw new IllegalArgumentException("No base dir set");

		return base.getAbsolutePath();
	}

	/**
	 * Property handling ...
	 * 
	 * @return
	 */

	public Properties getProperties() {
		if (fixup) {
			fixup = false;
			begin();
		}

		return properties;
	}

	public String getProperty(String key) {
		return getProperty(key, null);
	}

	public void mergeProperties(File file, boolean override) {
		if (file.isFile()) {
			try {
				Properties properties = loadProperties(file);
				mergeProperties(properties, override);
			} catch (Exception e) {
				error("Error loading properties file: " + file);
			}
		} else {
			if (!file.exists())
				error("Properties file does not exist: " + file);
			else
				error("Properties file must a file, not a directory: " + file);
		}
	}

	public void mergeProperties(Properties properties, boolean override) {
		for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
			String key = (String) e.nextElement();
			String value = properties.getProperty(key);
			if (override || !getProperties().containsKey(key))
				setProperty(key, value);
		}
	}

	public void setProperties(Properties properties) {
		doIncludes(getBase(), properties);
		this.properties.putAll(properties);
	}

	public void addProperties(File file) throws Exception {
		addIncluded(file);
		Properties p = loadProperties(file);
		setProperties(p);
	}

	public synchronized void addIncluded(File file) {
		if (included == null)
			included = new HashSet<File>();
		included.add(file);
	}

	/**
	 * Inspect the properties and if you find -includes parse the line included
	 * manifest files or properties files. The files are relative from the given
	 * base, this is normally the base for the analyzer.
	 * 
	 * @param ubase
	 * @param p
	 * @param done
	 * @throws IOException
	 * @throws IOException
	 */

	private void doIncludes(File ubase, Properties p) {
		String includes = p.getProperty(INCLUDE);
		if (includes != null) {
			includes = getReplacer().process(includes);
			p.remove(INCLUDE);
			Collection<String> clauses = parseHeader(includes).keySet();

			for (String value : clauses) {
				boolean fileMustExist = true;
				boolean overwrite = true;
				while (true) {
					if (value.startsWith("-")) {
						fileMustExist = false;
						value = value.substring(1).trim();
					} else if (value.startsWith("~")) {
						// Overwrite properties!
						overwrite = false;
						value = value.substring(1).trim();
					} else
						break;
				}
				try {
					File file = getFile(ubase, value).getAbsoluteFile();
					if (!file.isFile() && fileMustExist) {
						error("Included file " + file
								+ (file.exists() ? " does not exist" : " is directory"));
					} else
						doIncludeFile(file, overwrite, p);
				} catch (Exception e) {
					if (fileMustExist)
						error("Error in processing included file: " + value, e);
				}
			}
		}
	}

	/**
	 * @param file
	 * @param parent
	 * @param done
	 * @param overwrite
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public void doIncludeFile(File file, boolean overwrite, Properties target) throws Exception {
		if (included != null && included.contains(file)) {
			error("Cyclic or multiple include of " + file);
		} else {
			addIncluded(file);
			updateModified(file.lastModified(), file.toString());
			InputStream in = new FileInputStream(file);
			Properties sub;
			if (file.getName().toLowerCase().endsWith(".mf")) {
				sub = getManifestAsProperties(in);
			} else if (file.getName().endsWith(".xml"))
				sub = parseXml(file);
			else
				sub = loadProperties(in, file.getAbsolutePath());
			in.close();

			doIncludes(file.getParentFile(), sub);
			// make sure we do not override properties
			for (Map.Entry<?, ?> entry : sub.entrySet()) {
				if (overwrite || !target.containsKey(entry.getKey()))
					target.setProperty((String) entry.getKey(), (String) entry.getValue());
			}
		}
	}

	private Properties parseXml(File file) throws Exception {
		PomParser pp = new PomParser();
		try {
			return pp.getProperties(file);
		} finally {
			getInfo(pp);
		}
	}

	public void unsetProperty(String string) {
		getProperties().remove(string);

	}

	public boolean refresh() {
		plugins = null; // We always refresh our plugins

		if (propertiesFile == null)
			return false;

		updateModified(propertiesFile.lastModified(), "properties file");
		boolean changed = false;
		if (included != null) {
			for (File file : included) {

				if (file.exists() == false || file.lastModified() > modified) {
					updateModified(file.lastModified(), "include file: " + file);
					changed = true;
				}
			}
		}

		// System.out.println("Modified " + modified + " file: "
		// + propertiesFile.lastModified() + " diff "
		// + (modified - propertiesFile.lastModified()));

		// Date last = new Date(propertiesFile.lastModified());
		// Date current = new Date(modified);
		changed |= modified < propertiesFile.lastModified();
		if (changed) {
			included = null;
			properties.clear();
			setProperties(propertiesFile, base);
			propertiesChanged();
			return true;
		}
		return false;
	}

	public void propertiesChanged() {
	}

	/**
	 * Set the properties by file. Setting the properties this way will also set
	 * the base for this analyzer. After reading the properties, this will call
	 * setProperties(Properties) which will handle the includes.
	 * 
	 * @param propertiesFile
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public void setProperties(File propertiesFile) throws IOException {
		propertiesFile = propertiesFile.getAbsoluteFile();
		setProperties(propertiesFile, propertiesFile.getParentFile());
	}

	public void setProperties(File propertiesFile, File base) {
		this.propertiesFile = propertiesFile.getAbsoluteFile();
		setBase(base);
		try {
			if (propertiesFile.isFile()) {
				// System.out.println("Loading properties " + propertiesFile);
				long modified = propertiesFile.lastModified();
				if (modified > System.currentTimeMillis() + 100) {
					System.out.println("Huh? This is in the future " + propertiesFile);
					this.modified = System.currentTimeMillis();
				} else
					this.modified = modified;

				included = null;
				Properties p = loadProperties(propertiesFile);
				setProperties(p);
			} else {
				if (fileMustExist) {
					error("No such properties file: " + propertiesFile);
				}
			}
		} catch (IOException e) {
			error("Could not load properties " + propertiesFile);
		}
	}

	protected void begin() {
		if (isTrue(getProperty(PEDANTIC)))
			setPedantic(true);
	}

	public static boolean isTrue(String value) {
		if (value == null)
			return false;

		return !"false".equalsIgnoreCase(value);
	}

	/**
	 * Get a property with a proper default
	 * 
	 * @param headerName
	 * @param deflt
	 * @return
	 */
	public String getProperty(String key, String deflt) {
		String value = null;
		Processor source = this;

		if (filter != null && filter.contains(key)) {
			value = (String) getProperties().get(key);
		} else {
			while (source != null) {
				value = (String) source.getProperties().get(key);
				if (value != null)
					break;

				source = source.getParent();
			}
		}

		if (value != null)
			return getReplacer().process(value, source);
		else if (deflt != null)
			return getReplacer().process(deflt, this);
		else
			return null;
	}

	/**
	 * Helper to load a properties file from disk.
	 * 
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public Properties loadProperties(File file) throws IOException {
		updateModified(file.lastModified(), "Properties file: " + file);
		InputStream in = new FileInputStream(file);
		Properties p = loadProperties(in, file.getAbsolutePath());
		in.close();
		return p;
	}

	Properties loadProperties(InputStream in, String name) throws IOException {
		int n = name.lastIndexOf('/');
		if (n > 0)
			name = name.substring(0, n);
		if (name.length() == 0)
			name = ".";

		try {
			Properties p = new Properties();
			p.load(in);
			return replaceAll(p, "\\$\\{\\.\\}", name);
		} catch (Exception e) {
			error("Error during loading properties file: " + name + ", error:" + e);
			return new Properties();
		}
	}

	/**
	 * Replace a string in all the values of the map. This can be used to
	 * preassign variables that change. I.e. the base directory ${.} for a
	 * loaded properties
	 */

	public static Properties replaceAll(Properties p, String pattern, String replacement) {
		Properties result = new Properties();
		for (Iterator<Map.Entry<Object, Object>> i = p.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Object, Object> entry = i.next();
			String key = (String) entry.getKey();
			String value = (String) entry.getValue();
			value = value.replaceAll(pattern, replacement);
			result.put(key, value);
		}
		return result;
	}

	/**
	 * Merge the attributes of two maps, where the first map can contain
	 * wildcarded names. The idea is that the first map contains patterns (for
	 * example *) with a set of attributes. These patterns are matched against
	 * the found packages in actual. If they match, the result is set with the
	 * merged set of attributes. It is expected that the instructions are
	 * ordered so that the instructor can define which pattern matches first.
	 * Attributes in the instructions override any attributes from the actual.<br/>
	 * 
	 * A pattern is a modified regexp so it looks like globbing. The * becomes a
	 * .* just like the ? becomes a .?. '.' are replaced with \\. Additionally,
	 * if the pattern starts with an exclamation mark, it will remove that
	 * matches for that pattern (- the !) from the working set. So the following
	 * patterns should work:
	 * <ul>
	 * <li>com.foo.bar</li>
	 * <li>com.foo.*</li>
	 * <li>com.foo.???</li>
	 * <li>com.*.[^b][^a][^r]</li>
	 * <li>!com.foo.* (throws away any match for com.foo.*)</li>
	 * </ul>
	 * Enough rope to hang the average developer I would say.
	 * 
	 * 
	 * @param instructions
	 *            the instructions with patterns. A
	 * @param actual
	 *            the actual found packages
	 */

	public static Map<String, Map<String, String>> merge(String type,
			Map<String, Map<String, String>> instructions, Map<String, Map<String, String>> actual,
			Set<String> superfluous, Map<String, Map<String, String>> ignored) {
		Map<String, Map<String, String>> toVisit = new HashMap<String, Map<String, String>>(actual); // we
		// do
		// not
		// want
		// to
		// ruin
		// our
		// original
		Map<String, Map<String, String>> result = newMap();
		for (Iterator<String> i = instructions.keySet().iterator(); i.hasNext();) {
			String instruction = i.next();
			String originalInstruction = instruction;

			Map<String, String> instructedAttributes = instructions.get(instruction);

			// Check if we have a fixed (starts with '=') or a
			// duplicate name. A fixed name is added to the output without
			// checking against the contents. Duplicates are marked
			// at the end. In that case we do not pick up any contained
			// information but just add them to the output including the
			// marker.
			if (instruction.startsWith("=")) {
				result.put(instruction.substring(1), instructedAttributes);
				superfluous.remove(originalInstruction);
				continue;
			}
			if (isDuplicate(instruction)) {
				result.put(instruction, instructedAttributes);
				superfluous.remove(originalInstruction);
				continue;
			}

			Instruction instr = Instruction.getPattern(instruction);

			for (Iterator<String> p = toVisit.keySet().iterator(); p.hasNext();) {
				String packageName = p.next();

				if (instr.matches(packageName)) {
					superfluous.remove(originalInstruction);
					if (!instr.isNegated()) {
						Map<String, String> newAttributes = new HashMap<String, String>();
						newAttributes.putAll(actual.get(packageName));
						newAttributes.putAll(instructedAttributes);
						result.put(packageName, newAttributes);
					} else if (ignored != null) {
						ignored.put(packageName, new HashMap<String, String>());
					}
					p.remove(); // Can never match again for another pattern
				}
			}

		}
		return result;
	}

	/**
	 * Print a standard Map based OSGi header.
	 * 
	 * @param exports
	 *            map { name => Map { attribute|directive => value } }
	 * @return the clauses
	 */
	public static String printClauses(Map<String, Map<String, String>> exports,
			String allowedDirectives) {
		return printClauses(exports, allowedDirectives, false);
	}

	public static String printClauses(Map<String, Map<String, String>> exports,
			String allowedDirectives, boolean checkMultipleVersions) {
		StringBuffer sb = new StringBuffer();
		String del = "";
		for (Iterator<String> i = exports.keySet().iterator(); i.hasNext();) {
			String name = i.next();
			Map<String, String> clause = exports.get(name);

			// We allow names to be duplicated in the input
			// by ending them with '~'. This is necessary to use
			// the package names as keys. However, we remove these
			// suffixes in the output so that you can set multiple
			// exports with different attributes.
			String outname = removeDuplicateMarker(name);
			sb.append(del);
			sb.append(outname);
			printClause(clause, allowedDirectives, sb);
			del = ",";
		}
		return sb.toString();
	}

	public static void printClause(Map<String, String> map, String allowedDirectives,
			StringBuffer sb) {

		for (Iterator<String> j = map.keySet().iterator(); j.hasNext();) {
			String key = j.next();

			// Skip directives we do not recognize
			if (!key.startsWith("x-") && key.endsWith(":")
					&& (allowedDirectives == null || allowedDirectives.indexOf(key) < 0))
				continue;

			String value = ((String) map.get(key)).trim();
			sb.append(";");
			sb.append(key);
			sb.append("=");

			boolean clean = (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value
					.length() - 1) == '"')
					|| Verifier.TOKEN.matcher(value).matches();
			if (!clean)
				sb.append("\"");
			sb.append(value);
			if (!clean)
				sb.append("\"");
		}
	}

	public Macro getReplacer() {
		if (replacer == null)
			return replacer = new Macro(this, getMacroDomains());
		else
			return replacer;
	}

	/**
	 * This should be overridden by subclasses to add extra macro command
	 * domains on the search list.
	 * 
	 * @return
	 */
	protected Object[] getMacroDomains() {
		return new Object[] {};
	}

	/**
	 * Return the properties but expand all macros. This always returns a new
	 * Properties object that can be used in any way.
	 * 
	 * @return
	 */
	public Properties getFlattenedProperties() {
		return getReplacer().getFlattenedProperties();

	}

	public void updateModified(long time, String reason) {
		if (time > lastModified) {
			lastModified = time;
		}
	}

	public long lastModified() {
		return lastModified;
	}

	/**
	 * Add or override a new property.
	 * 
	 * @param key
	 * @param value
	 */
	public void setProperty(String key, String value) {
		checkheader: for (int i = 0; i < headers.length; i++) {
			if (headers[i].equalsIgnoreCase(value)) {
				value = headers[i];
				break checkheader;
			}
		}
		getProperties().put(key, value);
	}

	/**
	 * Read a manifest but return a properties object.
	 * 
	 * @param in
	 * @return
	 * @throws IOException
	 */
	public static Properties getManifestAsProperties(InputStream in) throws IOException {
		Properties p = new Properties();
		Manifest manifest = new Manifest(in);
		for (Iterator<Object> it = manifest.getMainAttributes().keySet().iterator(); it.hasNext();) {
			Attributes.Name key = (Attributes.Name) it.next();
			String value = manifest.getMainAttributes().getValue(key);
			p.put(key.toString(), value);
		}
		return p;
	}

	public File getPropertiesFile() {
		return propertiesFile;
	}

	public void setFileMustExist(boolean mustexist) {
		fileMustExist = mustexist;
	}

	static public String read(InputStream in) throws Exception {
		InputStreamReader ir = new InputStreamReader(in, "UTF8");
		StringBuilder sb = new StringBuilder();

		try {
			char chars[] = new char[1000];
			int size = ir.read(chars);
			while (size > 0) {
				sb.append(chars, 0, size);
				size = ir.read(chars);
			}
		} finally {
			ir.close();
		}
		return sb.toString();
	}

	/**
	 * Join a list.
	 * 
	 * @param args
	 * @return
	 */
	public static String join(Collection<?> list, String delimeter) {
		return join(delimeter, list);
	}

	public static String join(String delimeter, Collection<?>... list) {
		StringBuilder sb = new StringBuilder();
		String del = "";
		for (Collection<?> l : list) {
			if (list != null) {
				for (Object item : l) {
					sb.append(del);
					sb.append(item);
					del = delimeter;
				}
			}
		}
		return sb.toString();
	}

	public static String join(Object[] list, String delimeter) {
		if (list == null)
			return "";
		StringBuilder sb = new StringBuilder();
		String del = "";
		for (Object item : list) {
			sb.append(del);
			sb.append(item);
			del = delimeter;
		}
		return sb.toString();
	}

	public static String join(Collection<?>... list) {
		return join(",", list);
	}

	public static <T> String join(T list[]) {
		return join(list, ",");
	}

	public static void split(String s, Collection<String> set) {

		String elements[] = s.trim().split(LIST_SPLITTER);
		for (String element : elements) {
			if (element.length() > 0)
				set.add(element);
		}
	}

	public static Collection<String> split(String s) {
		return split(s, LIST_SPLITTER);
	}

	public static Collection<String> split(String s, String splitter) {
		if (s != null)
			s = s.trim();
		if (s == null || s.trim().length() == 0)
			return Collections.emptyList();

		return Arrays.asList(s.split(splitter));
	}

	public static String merge(String... strings) {
		ArrayList<String> result = new ArrayList<String>();
		for (String s : strings) {
			if (s != null)
				split(s, result);
		}
		return join(result);
	}

	public boolean isExceptions() {
		return exceptions;
	}

	public void setExceptions(boolean exceptions) {
		this.exceptions = exceptions;
	}

	/**
	 * Make the file short if it is inside our base directory, otherwise long.
	 * 
	 * @param f
	 * @return
	 */
	public String normalize(String f) {
		if (f.startsWith(base.getAbsolutePath() + "/"))
			return f.substring(base.getAbsolutePath().length() + 1);
		else
			return f;
	}

	public String normalize(File f) {
		return normalize(f.getAbsolutePath());
	}

	public static String removeDuplicateMarker(String key) {
		int i = key.length() - 1;
		while (i >= 0 && key.charAt(i) == DUPLICATE_MARKER)
			--i;

		return key.substring(0, i + 1);
	}

	public static boolean isDuplicate(String name) {
		return name.length() > 0 && name.charAt(name.length() - 1) == DUPLICATE_MARKER;
	}

	public void setTrace(boolean x) {
		trace = x;
	}

	static class CL extends URLClassLoader {

		CL() {
			super(new URL[0], Processor.class.getClassLoader());
		}

		void add(URL url) {
			URL urls[] = getURLs();
			for (URL u : urls) {
				if (u.equals(url))
					return;
			}
			super.addURL(url);
		}

		public Class<?> loadClass(String name) throws NoClassDefFoundError {
			try {
				Class<?> c = super.loadClass(name);
				return c;
			} catch (Throwable t) {
				StringBuilder sb = new StringBuilder();
				sb.append(name);
				sb.append(" not found, parent:  ");
				sb.append(getParent());
				sb.append(" urls:");
				sb.append(Arrays.toString(getURLs()));
				sb.append(" exception:");
				sb.append(t);
				throw new NoClassDefFoundError(sb.toString());
			}
		}
	}

	private CL getLoader() {
		if (pluginLoader == null) {
			pluginLoader = new CL();
		}
		return pluginLoader;
	}

	/*
	 * Check if this is a valid project.
	 */
	public boolean exists() {
		return base != null && base.isDirectory() && propertiesFile != null
				&& propertiesFile.isFile();
	}

	public boolean isOk() {
		return isFailOk() || (getErrors().size() == 0);
	}

	public boolean isPerfect() {
		return getErrors().size() == 0 && getWarnings().size() == 0;
	}

	public void setForceLocal(Collection<String> local) {
		filter = local;
	}

	/**
	 * Answer if the name is a missing plugin's command name. If a bnd file
	 * contains the command name of a plugin, and that plugin is not available,
	 * then an error is reported during manifest calculation. This allows the
	 * plugin to fail to load when it is not needed.
	 * 
	 * We first get the plugins to ensure it is properly initialized.
	 * 
	 * @param name
	 * @return
	 */
	public boolean isMissingPlugin(String name) {
		getPlugins();
		return missingCommand != null && missingCommand.contains(name);
	}

	/**
	 * Append two strings to for a path in a ZIP or JAR file. It is guaranteed
	 * to return a string that does not start, nor ends with a '/', while it is
	 * properly separated with slashes. Double slashes are properly removed.
	 * 
	 * <pre>
	 *  &quot;/&quot; + &quot;abc/def/&quot; becomes &quot;abc/def&quot;
	 *  
	 * &#064;param prefix
	 * &#064;param suffix
	 * &#064;return
	 * 
	 */
	public static String appendPath(String... parts) {
		StringBuilder sb = new StringBuilder();
		boolean lastSlash = true;
		for (String part : parts) {
			for (int i = 0; i < part.length(); i++) {
				char c = part.charAt(i);
				if (c == '/') {
					if (!lastSlash)
						sb.append('/');
					lastSlash = true;
				} else {
					sb.append(c);
					lastSlash = false;
				}
			}
			if (!lastSlash & sb.length() > 0) {
				sb.append('/');
				lastSlash = true;
			}
		}
		if (lastSlash && sb.length() > 0)
			sb.deleteCharAt(sb.length() - 1);

		return sb.toString();
	}

	/**
	 * Parse the a=b strings and return a map of them.
	 * 
	 * @param attrs
	 * @param clazz
	 * @return
	 */
	public static Map<String, String> doAttrbutes(Object[] attrs, Clazz clazz, Macro macro) {
		if (attrs == null || attrs.length == 0)
			return Collections.emptyMap();

		Map<String, String> map = newMap();
		for (Object a : attrs) {
			String attr = (String) a;
			int n = attr.indexOf("=");
			if (n > 0) {
				map.put(attr.substring(0, n), macro.process(attr.substring(n + 1)));
			} else
				throw new IllegalArgumentException(String.format(
						"Invalid attribute on package-info.java in %s , %s. Must be <key>=<name> ",
						clazz, attr));
		}
		return map;
	}

	public static String append(String... strings) {
		List<String> result = Create.list();
		for (String s : strings) {
			result.addAll(split(s));
		}
		return join(result);
	}

	/**
	 * Add an additional plugin that is always added to the plugins. This safes
	 * you from going through the string based approach.
	 */

	public synchronized void addBasicPlugin(Object o) {
		basicPlugins.add(o);
		if (plugins != null)
			plugins.add(o);
	}

	/**
	 * Remove an additional plugin that is always added to the plugins.
	 */

	public synchronized void removeBasicPlugin(Object o) {
		basicPlugins.remove(o);
		if (plugins != null)
			plugins.remove(o);
	}

	public synchronized Class<?> getClass(String type, File jar) throws Exception {
		CL cl = getLoader();
		cl.add(jar.toURI().toURL());
		return cl.loadClass(type);
	}

	static public void delete(File target) {
		if (target.getParentFile() == null)
			throw new IllegalArgumentException("Can not delete root!");
		if (!target.exists())
			return;

		if (target.isDirectory()) {
			File sub[] = target.listFiles();
			for (File s : sub)
				delete(s);
		}
		target.delete();
	}

	public boolean isTrace() {
		return trace;
	}

	public static Executor getExecutor() {
		if (executor == null)
			executor = Executors.newCachedThreadPool();
		return executor;
	}

	public static long getDuration(String tm, long dflt) {
		if (tm == null)
			return dflt;

		tm = tm.toUpperCase();
		TimeUnit unit = TimeUnit.MILLISECONDS;
		Matcher m = Pattern
				.compile(
						"\\s*(\\d+)\\s*(NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)?")
				.matcher(tm);
		if (m.matches()) {
			long duration = Long.parseLong(tm);
			String u = m.group(2);
			if (u != null)
				unit = TimeUnit.valueOf(u);
			duration = TimeUnit.MILLISECONDS.convert(duration, unit);
			return duration;
		}
		return dflt;
	}

}
