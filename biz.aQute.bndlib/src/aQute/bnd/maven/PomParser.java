package aQute.bnd.maven;

import java.io.*;
import java.util.*;

import javax.xml.parsers.*;
import javax.xml.xpath.*;

import org.w3c.dom.*;

import aQute.lib.io.*;
import aQute.lib.osgi.*;

/**
 * Provides a way to parse a maven pom as properties.
 * 
 * This provides most of the maven elements as properties. It also
 * provides pom.scope.[compile|test|runtime|provided|system] properties
 * that can be appended to the build and run path. That is, they are
 * in the correct format for this.
 */
public class PomParser extends Processor {
	static DocumentBuilderFactory	dbf			= DocumentBuilderFactory.newInstance();
	static XPathFactory				xpathf		= XPathFactory.newInstance();
	static Set<String>				multiple	= new HashSet<String>();
	static Set<String>				skip	= new HashSet<String>();

	static {
		dbf.setNamespaceAware(false);
		
		// Set all elements that need enumeration of their elements
		// these will not use the name of the subelement but instead
		// they use an index from 0..n
		multiple.add("mailingLists");
		multiple.add("pluginRepositories");
		multiple.add("repositories");
		multiple.add("resources");
		multiple.add("executions");
		multiple.add("goals");
		multiple.add("includes");
		multiple.add("excludes");

		// These properties are not very useful and
		// pollute the property space.
		skip.add("plugins");
		skip.add("dependencies");
		skip.add("reporting");
		skip.add("extensions");
		
	}

	public Properties getProperties(File pom) throws Exception {
		DocumentBuilder db = dbf.newDocumentBuilder();
		XPath xpath = xpathf.newXPath();
		pom = pom.getAbsoluteFile();
		Document doc = db.parse(pom);
		Properties p = new Properties();

		// Check if there is a parent pom
		String relativePath = xpath.evaluate("project/parent/relativePath", doc);
		if (relativePath != null && !relativePath.isEmpty()) {
			File parentPom = IO.getFile(pom.getParentFile(), relativePath);
			if (parentPom.isFile()) {
				Properties parentProps = getProperties(parentPom);
				p.putAll(parentProps);
			} else {
				error("Parent pom for %s is not an existing file (could be directory): %s", pom, parentPom);
			}
		}

		Element e = doc.getDocumentElement();
		traverse("pom", e, p);

		String scopes[] = { "provided", "runtime", "test", "system" };
		NodeList set = (NodeList) xpath.evaluate("//dependency[not(scope) or scope='compile']", doc,
				XPathConstants.NODESET);
		if (set.getLength() != 0)
			p.put("pom.scope.compile", toBsn(set));

		for (String scope : scopes) {
			set = (NodeList) xpath.evaluate("//dependency[scope='" + scope + "']", doc,
					XPathConstants.NODESET);
			if (set.getLength() != 0)
				p.put("pom.scope." + scope, toBsn(set));
		}

		return p;
	}

	private Object toBsn(NodeList set) throws XPathExpressionException {
		XPath xpath = xpathf.newXPath();
		StringBuilder sb = new StringBuilder();
		String del = "";
		for (int i = 0; i < set.getLength(); i++) {
			Node child = set.item(i);
			String version = xpath.evaluate("version", child);
			sb.append(del);
			sb.append(xpath.evaluate("groupId", child));
			sb.append(".");
			sb.append(xpath.evaluate("artifactId", child));
			if (version != null && !version.trim().isEmpty()) {
				sb.append(";version=");
				sb.append( Analyzer.cleanupVersion(version));
			}
			del = ", ";
		}
		return sb.toString();
	}

	/**
	 * The maven POM is quite straightforward, it is basically a structured property file.
	 * @param name
	 * @param parent
	 * @param p
	 */
	static void traverse(String name, Node parent, Properties p) {
		if ( skip.contains(parent.getNodeName()))
			return;
		
		NodeList children = parent.getChildNodes();
		if (multiple.contains(parent.getNodeName())) {
			int n = 0;
			for (int i = 0; i < children.getLength(); i++) {
				Node child = children.item(i);
				if (!(child instanceof Text)) {

					traverse(name + "." + n++, child, p);
				}
			}
		} else {
			for (int i = 0; i < children.getLength(); i++) {
				Node child = children.item(i);
				if (child instanceof Text) {
					String value = child.getNodeValue().trim();
					if (!value.isEmpty()) {
						p.put(name, value);
					}
				} else {
					traverse(name + "." + child.getNodeName(), child, p);
				}
			}
		}
	}
}
