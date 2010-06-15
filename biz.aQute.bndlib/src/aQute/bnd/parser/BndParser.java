package aQute.bnd.parser;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import aQute.lib.osgi.*;
import aQute.libg.generics.*;
import aQute.libg.reporter.*;

public class BndParser extends Properties {
	private static final long		serialVersionUID	= 1L;
	final Map<String, LineRecord>	locations			= Create.map();
	long							lastModified;
	int								line;
	Processor						processor;
	Pattern							HEADER				= Pattern
																.compile("([A-Za-z0-9][A-Za-z0-9-_])*\\s*(=|:)\\s*(.*)");
	Pattern							COMMENT				= Pattern.compile("\\s*(#.*)?");

	class LineRecord implements Reporter.Where {
		public LineRecord(File file, int line) {
			this.lineno = line;
			this.file = file;
		}

		int					lineno;
		final List<String>	lines	= new ArrayList<String>();
		File				file;
		StringBuilder		comments;

		public File getFile() {
			return file;
		}

		public int getLineNo() {
			return lineno;
		}
	}

	public BndParser(Processor r, Properties deflt) {
		super(deflt);
		this.processor = r;
	}

	public BndParser(Processor r) {
		this.processor = r;
	}

	public void parse(File file, Set<File> done) throws Exception {
		done.add(file);

		if (!file.isFile()) {
			processor.error("No such file to parse: %s", file);
		} else {
			InputStream in = new FileInputStream(file);
			try {
				Reader r = new InputStreamReader(in, "UTF-8");
				try {
					parse(file, r, done);
				} finally {
					r.close();
				}
			} finally {
				in.close();
			}
		}
	}

	private void parse(File file, Reader r, Set<File> done) throws Exception {
		boolean manifest = file.getName().endsWith(".MF");

		BufferedReader br = new BufferedReader(r);
		
		if ( manifest ) 
			parseManifest(file, br);
		else
			parseProperties(file,br, done);
		
	}

	private void parseProperties(File file, BufferedReader br, Set<File> done) throws Exception {		
		String line = br.readLine();
		StringBuilder comments = new StringBuilder();
		while (line != null) {
			LineRecord location = new LineRecord(file, this.line);
			StringBuilder sb = new StringBuilder("\\");
			do {
				sb.deleteCharAt(sb.length() - 1);
				location.lines.add(line);
				sb.append(line);
				line = br.readLine();
				this.line++;
			} while (line != null && line.endsWith("\\"));

			location.comments = comments;
			comments.delete(0, comments.length());

			Matcher h = HEADER.matcher(sb);
			if (h.matches()) {
				String key = h.group(1);
				String value = h.group(2);
				setProperty(key, value);
			} else if (COMMENT.matcher(sb).matches()) {
				String comment = sb.toString().trim();
				if (comment.length() > 0) {
					comments.append(sb);
					comments.append("\n");
				}
			} else {
				processor.error("Invalid header %s: %s", location, line);
			}
		}
		String include = getProperty(Constants.INCLUDE);
		if (include != null) {
			remove(Constants.INCLUDE);
			Collection<String> clauses = processor.parseHeader(include).keySet();
			for (String value : clauses) {
				include(file, value, done);
			}
		}
	}


	private String parseManifest(File file, BufferedReader br) throws Exception {
		String line = br.readLine();
		if ( line == null || line.isEmpty())
			return "Invalid manifest file";
		
		while (line != null) {
			LineRecord location = new LineRecord(file, this.line);
			StringBuilder sb = new StringBuilder();
			while (true) {
				location.lines.add(line);
				sb.append(line);
				line = br.readLine();
				this.line++;
				
				if ( line != null && line.startsWith(" ")) {
					line = line.substring(1);
				} else
					break;
			}

			Matcher h = HEADER.matcher(sb);
			if (h.matches()) {
				String key = h.group(1);
				String value = h.group(2);
				setProperty(key, value);
			} else {
				return "Invalid manifest header: " + line;
			}
		}
		return null;
	}

	void include(File base, String include, Set<File> done) throws Exception {
		boolean fileMustExist = true;
		boolean overwrite = true;
		while (true) {
			if (include.startsWith("-")) {
				fileMustExist = false;
				include = include.substring(1).trim();
			} else if (include.startsWith("~")) {
				// Overwrite properties!
				overwrite = false;
				include = include.substring(1).trim();
			} else
				break;
		}

		File file = Processor.getFile(base, include).getAbsoluteFile();
		if (done.contains(file)) {
			processor.error("Include file already included at %s, chain is: %s", line, done);
			return;
		}
		if (!file.isFile()) {
			if (fileMustExist)
				processor.error("Include file at %s, not found: %s", line, file);

			return;
		}
		BndParser inc = new BndParser(processor);
		inc.parse(file, done);

		for (Object o : inc.keySet()) {
			String key = (String) o;
			if (overwrite || !containsKey(key)) {
				put(key, inc.get(key));
				locations.put(key, inc.locations.get(key));
			}
		}

		if (lastModified < inc.lastModified)
			lastModified = inc.lastModified;
	}

	public int getLineNo(String key) {
		LineRecord lr = locations.get(key);
		if (lr == null)
			return -1;
		return lr.lineno;
	}

	public int getLineNo(String key, String field) {
		LineRecord lr = locations.get(key);
		if (lr == null)
			return -1;

		int offset = 0;
		for (String s : lr.lines)
			if (s.indexOf(field) >= 0)
				return lr.lineno + offset;
			else
				offset++;

		return lr.lineno;
	}
}
