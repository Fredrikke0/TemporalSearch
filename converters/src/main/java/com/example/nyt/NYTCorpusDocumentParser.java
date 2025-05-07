package com.example.nyt;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * NYTCorpusDocumentParser <BR>
 * Created: Jun 17, 2008 <BR>
 * Author: Evan Sandhaus (sandhes@nytimes.com)<BR>
 * <P>
 * Class for parsing New York Times articles from NITF files.
 * <P>
 *
 * @author Evan Sandhaus
 *
 */
public class NYTCorpusDocumentParser {
	/** NITF Constant */
	private static final String CORRECTION_TEXT = "correction_text";

	/** NITF Constant */
	private static final String SERIES_NAME_TAG = "series.name";

	/** NITF Constant */
	private static final DateFormat format = new SimpleDateFormat(
			"yyyyMMdd'T'HHmmss");

	/** NITF Constant */
	private static final String TAGLINE_TAG = "tagline";

	/** NITF Constant */
	private static final String CLASS_ATTRIBUTE = "class";

	/** NITF Constant */
	private static final String CLASSIFIER_TAG = "classifier";

	/** NITF Constant */
	private static final String HL2_TAG = "hl2";

	/** NITF Constant */
	private static final String BLOCK_TAG = "block";

	/** NITF Constant */
	private static final String ABSTRACT_TAG = "abstract";

	/** NITF Constant */
	private static final String DATELINE_TAG = "dateline";

	/** NITF Constant */
	private static final String BYLINE_TAG = "byline";

	/** NITF Constant */
	private static final String HEDLINE_TAG = "hedline";

	/** NITF Constant */
	private static final String BODY_END_TAG = "body.end";

	/** NITF Constant */
	private static final String BODY_CONTENT_TAG = "body.content";

	/** NITF Constant */
	private static final String BODY_HEAD_TAG = "body.head";

	/** NITF Constant */
	private static final String TYPE_ATTRIBUTE = "type";

	/** NITF Constant */
	private static final String NAME_ATTRIBUTE = "name";

	/** NITF Constant */
	private static final String ITEM_LENGTH_ATTRIBUTE = "item-length";

	/** NITF Constant */
	private static final String EX_REF_ATTRIBUTE = "ex-ref";

	/** NITF Constant */
	private static final String SLUG_ATTRIBUTE = "slug";

	/** NITF Constant */
	private static final String PRINT_SECTION_ATTRIBUTE = "print_section";

	/** NITF Constant */
	private static final String PRINT_PAGE_NUMBER_ATTRIBUTE = "print_page_number";

	/** NITF Constant */
	private static final String DSK_ATTRIBUTE = "dsk";

	/** NITF Constant */
	private static final String HL1_TAG = "hl1";

	/** NITF Constant */
	private static final String CONTENT_ATTRIBUTE = "content";

	/** NITF Constant */
	private static final String DOC_ID_TAG = "doc-id";

	/** NITF Constant */
	private static final String IDENTIFIED_CONTENT_TAG = "identified-content";

	/** NITF Constant */
	private static final String ID_STRING_ATTRIBUTE = "id-string";

	/** NITF Constant */
	private static final String LOCATION_TAG = "location";

	/** NITF Constant */
	private static final String OBJECT_TITLE_TAG = "object.title";

	/** NITF Constant */
	private static final String PERSON_TAG = "person";

	/** NITF Constant */
	private static final String PUBDATA_TAG = "pubdata";

	/** NITF Constant */
	private static final String DOCDATA_TAG = "docdata";

	/** NITF Constant */
	private static final String META_TAG = "meta";

	/** NITF Constant */
	private static final String BODY_TAG = "body";

	/** NITF Constant */
	private static final String HEAD_TAG = "head";

	/** NITF Constant */
	private static final String NITF_TAG = "nitf";

	/** NITF Constant */
	private static final String ALTERNATE_URL_ATTRIBUTE = "alternate_url";

	/** NITF Constant */
	private static final String AUTHOR_INFO_ATTRIBUTE = "author_info";

	/** NITF Constant */
	private static final String DESCRIPTOR_ATTRIBUTE = "descriptor";

	/** NITF Constant */
	private static final String FULL_TEXT_ATTRIBUTE = "full_text";

	/** NITF Constant */
	private static final String INDEXING_SERVICE_ATTRIBUTE = "indexing_service";

	/** NITF Constant */
	private static final String LEAD_PARAGRAPH_ATTRIBUTE = "lead_paragraph";

	/** NITF Constant */
	private static final String NORMALIZED_BYLINE_ATTRIBUTE = "normalized_byline";

	/** NITF Constant */
	private static final String ONLINE_HEADLINE_ATTRIBUTE = "online_headline";

	/** NITF Constant */
	private static final String ONLINE_LEAD_PARAGRAPH_ATTRIBUTE = "online_lead_paragraph";

	/** NITF Constant */
	private static final String ONLINE_PRODUCER_ATTRIBUTE = "online_producer";

	/** NITF Constant */
	private static final String ONLINE_SECTIONS_ATTRIBUTE = "online_sections";

	/** NITF Constant */
	private static final String ORGANIZATION_TAG = "org";

	/** NITF Constant */
	private static final String P_TAG = "p";

	/** NITF Constant */
	private static final String PRINT_BYLINE_ATTRIBUTE = "print_byline";

	/** NITF Constant */
	private static final String PRINT_COLUMN_ATTRIBUTE = "print_column";

	/** NITF Constant */
	private static final String PUBLICATION_DAY_OF_MONTH_ATTRIBUTE = "publication_day_of_month";

	/** NITF Constant */
	private static final String PUBLICATION_MONTH_ATTRIBUTE = "publication_month";

	/** NITF Constant */
	private static final String PUBLICATION_YEAR_ATTRIBUTE = "publication_year";

	/** NITF Constant */
	private static final String PULICATION_DAY_OF_WEEK_ATTRIBUTE = "publication_day_of_week";

	/** NITF Constant */
	private static final String SERIES_NAME_ATTRIBUTE = "series_name";

	/** NITF Constant */
	private static final String SERIES_TAG = "series";

	/** NITF Constant */
	private static final String TAXONOMIC_CLASSIFIER_ATTRIBUTE = "taxonomic_classifier";

	/** NITF Constant */
	private static final String BANNER_ATTRIBUTE = "banner";

	/** NITF Constant */
	private static final String CORRECTION_DATE_ATTRIBUTE = "correction_date";

	/** NITF Constant */
	private static final String FEATURE_PAGE_ATTRIBUTE = "feature_page";

	/** NITF Constant */
	private static final String COLUMN_NAME_ATTRIBUTE = "column_name";

	/** NITF Constant */
	private static final String TYPES_OF_MATERIAL_ATTRIBUTE = "types_of_material";

	/** NITF Constant */
	private static final String NAMES_ATTRIBUTE = "names";

	/** NITF Constant */
	private static final String BIOGRAPHICAL_CATEGORIES_ATTRIBUTE = "biographical_categories";

	/** NITF Constant */
	public static final String DATE_PUBLICATION_ATTRIBUTE = "date.publication";

	/** NITF Constant */
	private static final String GENERAL_DESCRIPTOR_ATTRIBUTE = "general_descriptor";

	/**
	 * Parses a NYTimes document from a file.
	 *
	 * @param file File to parse.
	 * @param validating Whether to validate against DTD.
	 * @return The parsed NYTCorpusDocument.
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromFile(File file,
			boolean validating) {
		Document document = null;
		try {
			document = getDOMObject(file.getAbsolutePath(), validating);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return parseNYTCorpusDocumentFromDOMDocument(file, document);
	}

	/**
	 * Parses a NYTimes document from an InputStream.
	 *
	 * @param is InputStream to parse.
	 * @param validating Whether to validate against DTD.
	 * @return The parsed NYTCorpusDocument.
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromInputStream(InputStream is,
			boolean validating) {
		Document document = null;
		try {
			document = getDOMObjectFromInputStream(is, validating);
		} catch (SAXException e) {
		    System.err.println("SAXException while parsing InputStream: " + e.getMessage());
		    return null; // Or rethrow as a RuntimeException if preferred
		} catch (IOException e) {
		    System.err.println("IOException while parsing InputStream: " + e.getMessage());
		    return null;
		} catch (ParserConfigurationException e) {
		    System.err.println("ParserConfigurationException while parsing InputStream: " + e.getMessage());
		    return null;
		} catch (Exception e) { // Catch any other unexpected exceptions
		    System.err.println("Unexpected exception while parsing InputStream: " + e.getMessage());
		    e.printStackTrace(); // For debugging
		    return null;
		}
		return parseNYTCorpusDocumentFromDOMDocument(null, document);
	}

	/**
	 * Parses a NYTimes document from a W3C Document object.
	 *
	 * @param file The source file (can be null if parsing from InputStream directly).
	 * @param document The W3C Document object.
	 * @return The parsed NYTCorpusDocument.
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromDOMDocument(
			File file, Document document) {
		NYTCorpusDocument ldcDocument = new NYTCorpusDocument();
		ldcDocument.setSourceFile(file);

		List<Node> matches = getNodesByTagName(document, NITF_TAG);
		if (matches.size() > 0) {
			handleNITFNode(matches.get(0), ldcDocument);
		}

		return ldcDocument;
	}

	private void handleNITFNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (HEAD_TAG.equals(child.getNodeName())) {
				handleHeadNode(child, ldcDocument);
			} else if (BODY_TAG.equals(child.getNodeName())) {
				handleBodyNode(child, ldcDocument);
			}
		}
	}

	private void handleBodyNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (BODY_HEAD_TAG.equals(child.getNodeName())) {
				handleBodyHead(child, ldcDocument);
			} else if (BODY_CONTENT_TAG.equals(child.getNodeName())) {
				handleBodyContent(child, ldcDocument);
			} else if (BODY_END_TAG.equals(child.getNodeName())) {
				handleBodyEnd(child, ldcDocument);
			}
		}
	}

	private void handleBodyHead(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (HEDLINE_TAG.equals(child.getNodeName())) {
				handleHeadlineNode(child, ldcDocument);
			} else if (BYLINE_TAG.equals(child.getNodeName())) {
				handleBylineNode(child, ldcDocument);
			} else if (DATELINE_TAG.equals(child.getNodeName())) {
				handleDatelineNode(ldcDocument, child);
			} else if (ABSTRACT_TAG.equals(child.getNodeName())) {
				handleAbstractNode(child, ldcDocument);
			}
		}
	}

	private void handleDatelineNode(NYTCorpusDocument ldcDocument, Node child) {
		ldcDocument.setDateline(getAllText(child));
	}

	private void handleAbstractNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (P_TAG.equals(child.getNodeName())) {
				ldcDocument.setArticleAbstract(getAllText(child));
			}
		}
	}

	private void handleBylineNode(Node node, NYTCorpusDocument ldcDocument) {
		ldcDocument.setByline(getAllText(node));
		if (node.getAttributes() != null) {
			Node classAttr = node.getAttributes().getNamedItem(CLASS_ATTRIBUTE);
			if (classAttr != null) {
				if ("normalized_byline".equals(classAttr.getNodeValue())) {
					ldcDocument.setNormalizedByline(getAllText(node));
				} else if ("online_producer".equals(classAttr.getNodeValue())) {
					// Handle online producer if necessary
				}
			}
		}
	}

	private void handleHeadlineNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (HL1_TAG.equals(child.getNodeName())) {
				ldcDocument.setHeadline(getAllText(child));
			} else if (HL2_TAG.equals(child.getNodeName())) {
				ldcDocument.setKicker(getAllText(child));
			}
		}
	}

	private void handleBodyContent(Node node, NYTCorpusDocument ldcDocument) {
		StringBuffer body = new StringBuffer();
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (BLOCK_TAG.equals(child.getNodeName())) {
				body.append(parseBlock(child) + "\n");
			}
		}
		ldcDocument.setBody(body.toString());
	}

	private void handleBlockNode(Node node, NYTCorpusDocument ldcDocument) {
		String className = getAttributeValue(node, CLASS_ATTRIBUTE);
		if (FULL_TEXT_ATTRIBUTE.equals(className)) {
			ldcDocument.setBody(parseBlock(node));
		} else if (LEAD_PARAGRAPH_ATTRIBUTE.equals(className)) {
			ldcDocument.setLeadParagraph(parseBlock(node));
		} else if (ONLINE_LEAD_PARAGRAPH_ATTRIBUTE.equals(className)) {
			ldcDocument.setOnlineLeadParagraph(parseBlock(node));
		} else if (CORRECTION_TEXT.equals(className)) {
			ldcDocument.setCorrectionText(parseBlock(node));
		} else if (TAGLINE_TAG.equals(className)) {
			// Handle tagline if necessary
		}
	}

	private void handleBodyEnd(Node node, NYTCorpusDocument ldcDocument) {
		// Do nothing
	}

	private void handleHeadNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (META_TAG.equals(child.getNodeName())) {
				handleMetaNode(child, ldcDocument);
			} else if (DOCDATA_TAG.equals(child.getNodeName())) {
				handleDocdataNode(child, ldcDocument);
			} else if (PUBDATA_TAG.equals(child.getNodeName())) {
				handlePubdata(child, ldcDocument);
			} else if (TITLE_TAG.equals(child.getNodeName())) {
				// Generally the headline, but could be something else.
				// For now, assume headline handles this.
			}
		}
	}

	private void handleDocdataNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (DOC_ID_TAG.equals(child.getNodeName())) {
				handleDocumentIdNode(ldcDocument, child);
			} else if (SERIES_TAG.equals(child.getNodeName())) {
				handleSeriesNode(ldcDocument, child);
			} else if (IDENTIFIED_CONTENT_TAG.equals(child.getNodeName())) {
				handleIdentifiedContent(child, ldcDocument);
			}
		}
	}

	private void handlePubdata(Node node, NYTCorpusDocument ldcDocument) {
		String dateString = getAttributeValue(node, DATE_PUBLICATION_ATTRIBUTE);
		if (dateString != null) {
			try {
				Date date = format.parse(dateString);
				ldcDocument.setPublicationDate(date);
			} catch (ParseException e) {
				System.err.println("Unable to parse publication date: " + dateString);
			}
		}

		String name = getAttributeValue(node, NAME_ATTRIBUTE);
		if ("The New York Times".equals(name)) {
			ldcDocument.setCredit(name);
		}

		String exRef = getAttributeValue(node, EX_REF_ATTRIBUTE);
		if (exRef != null) {
			try {
				ldcDocument.setUrl(new URL(exRef));
			} catch (MalformedURLException e) {
				System.err.println("Malformed URL for ex-ref: " + exRef);
			}
		}

		String itemLength = getAttributeValue(node, ITEM_LENGTH_ATTRIBUTE);
		if (itemLength != null) {
			try {
				ldcDocument.setWordCount(Integer.parseInt(itemLength));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse item-length: " + itemLength);
			}
		}

		// Additional pubdata attributes (less common, might need specific handling)
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			// Example: <revision-history> or other specific pubdata elements
			// if ("revision-history".equals(child.getNodeName())) { ... }
		}
	}

	private void handleIdentifiedContent(Node node,
			NYTCorpusDocument ldcDocument) {
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (CLASSIFIER_TAG.equals(child.getNodeName())) {
				handleClassifierNode(child, ldcDocument);
			} else if (LOCATION_TAG.equals(child.getNodeName())) {
				handleLocationNode(child, ldcDocument);
			} else if (PERSON_TAG.equals(child.getNodeName())) {
				handlePersonNode(child, ldcDocument);
			} else if (ORGANIZATION_TAG.equals(child.getNodeName())) {
				handleOrganizationNode(child, ldcDocument);
			} else if (OBJECT_TITLE_TAG.equals(child.getNodeName())) {
				handleObjectTitleNode(child, ldcDocument);
			}
		}
	}

	private void handleObjectTitleNode(Node node,
			NYTCorpusDocument ldcDocument) {
		String type = getAttributeValue(node, TYPE_ATTRIBUTE);
		String content = getAllText(node);
		if ("online_title".equals(type)) {
			ldcDocument.getOnlineTitles().add(content);
		} else if (type == null || "title".equals(type)) { // Default or explicit title
			ldcDocument.getTitles().add(content);
		}
	}

	private void handlePersonNode(Node node, NYTCorpusDocument ldcDocument) {
		String type = getAttributeValue(node, TYPE_ATTRIBUTE);
		String content = getAllText(node);
		if ("online_person".equals(type)) {
			ldcDocument.getOnlinePeople().add(content);
		} else if (type == null || "person".equals(type)) { // Default or explicit person
			ldcDocument.getPeople().add(content);
		}
	}

	private void handleOrganizationNode(Node node,
			NYTCorpusDocument ldcDocument) {
		String type = getAttributeValue(node, TYPE_ATTRIBUTE);
		String content = getAllText(node);
		if ("online_organization".equals(type)) {
			ldcDocument.getOnlineOrganizations().add(content);
		} else if (type == null || "organization".equals(type)) { // Default or explicit org
			ldcDocument.getOrganizations().add(content);
		}
	}

	private void handleLocationNode(Node node, NYTCorpusDocument ldcDocument) {
		String type = getAttributeValue(node, TYPE_ATTRIBUTE);
		String content = getAllText(node);
		if ("online_location".equals(type)) {
			ldcDocument.getOnlineLocations().add(content);
		} else if (type == null || "location".equals(type)) { // Default or explicit location
			ldcDocument.getLocations().add(content);
		}
	}

	private void handleSeriesNode(NYTCorpusDocument ldcDocument, Node child) {
		String seriesName = getAttributeValue(child, SERIES_NAME_TAG);
		if (seriesName != null) {
			ldcDocument.setSeriesName(seriesName);
		}
	}

	private void handleDocumentIdNode(NYTCorpusDocument ldcDocument, Node child) {
		String idString = getAttributeValue(child, ID_STRING_ATTRIBUTE);
		if (idString != null) {
			try {
				ldcDocument.setGuid(Integer.parseInt(idString));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse doc-id id-string: " + idString);
			}
		}
	}

	private void handleClassifierNode(Node node, NYTCorpusDocument ldcDocument) {
		String type = getAttributeValue(node, TYPE_ATTRIBUTE);
		String content = getAllText(node);

		if (content == null || content.trim().isEmpty()) return;

		if ("descriptor".equals(type)) {
			ldcDocument.getDescriptors().add(content);
		} else if ("online_descriptor".equals(type)) {
			ldcDocument.getOnlineDescriptors().add(content);
		} else if ("general_descriptor".equals(type)) {
			ldcDocument.getGeneralOnlineDescriptors().add(content);
		} else if ("taxonomic_classifier".equals(type)) {
			String[] parts = content.split("/");
			for (String part : parts) {
				if (part != null && !part.trim().isEmpty()) {
					ldcDocument.getTaxonomicClassifiers().add(part.trim());
				}
			}
		} else if ("types_of_material".equals(type)) {
			ldcDocument.getTypesOfMaterial().add(content);
		} else if ("biographical_categories".equals(type)) {
			ldcDocument.getBiographicalCategories().add(content);
		}
	}

	private void handleMetaNode(Node node, NYTCorpusDocument ldcDocument) {
		String name = getAttributeValue(node, NAME_ATTRIBUTE);
		String content = getAttributeValue(node, CONTENT_ATTRIBUTE);

		if (name == null || content == null) return;

		if (ALTERNATE_URL_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setAlternateURL(new URL(content));
			} catch (MalformedURLException e) {
				System.err.println("Malformed alternate_url: " + content);
			}
		} else if (AUTHOR_INFO_ATTRIBUTE.equals(name)) {
			ldcDocument.setAuthorBiography(content);
		} else if (BANNER_ATTRIBUTE.equals(name)) {
			ldcDocument.setBanner(content);
		} else if (COLUMN_NAME_ATTRIBUTE.equals(name)) {
			ldcDocument.setColumnName(content);
		} else if (CORRECTION_DATE_ATTRIBUTE.equals(name)) {
			// Correction date format not specified, assuming yyyyMMdd
			try {
				DateFormat correctionDateFormat = new SimpleDateFormat("yyyyMMdd");
				ldcDocument.setCorrectionDate(correctionDateFormat.parse(content));
			} catch (ParseException e) {
				System.err.println("Unable to parse correction_date: " + content);
			}
		} else if (DSK_ATTRIBUTE.equals(name)) {
			ldcDocument.setNewsDesk(content);
		} else if (FEATURE_PAGE_ATTRIBUTE.equals(name)) {
			ldcDocument.setFeaturePage(content);
		} else if (NORMALIZED_BYLINE_ATTRIBUTE.equals(name)) {
			ldcDocument.setNormalizedByline(content);
		} else if (ONLINE_HEADLINE_ATTRIBUTE.equals(name)) {
			ldcDocument.setOnlineHeadline(content);
		} else if (ONLINE_SECTIONS_ATTRIBUTE.equals(name)) {
			ldcDocument.setOnlineSection(content);
		} else if (PRINT_BYLINE_ATTRIBUTE.equals(name)) {
			// This might be redundant with the main byline tag handler
			if (ldcDocument.getByline() == null || ldcDocument.getByline().isEmpty()) {
			    ldcDocument.setByline(content);
			}
		} else if (PRINT_COLUMN_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setColumnNumber(Integer.parseInt(content));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse print_column: " + content);
			}
		} else if (PRINT_PAGE_NUMBER_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setPage(Integer.parseInt(content));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse print_page_number: " + content);
			}
		} else if (PRINT_SECTION_ATTRIBUTE.equals(name)) {
			ldcDocument.setSection(content);
		} else if (PUBLICATION_DAY_OF_MONTH_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setPublicationDayOfMonth(Integer.parseInt(content));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse publication_day_of_month: " + content);
			}
		} else if (PUBLICATION_MONTH_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setPublicationMonth(Integer.parseInt(content));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse publication_month: " + content);
			}
		} else if (PUBLICATION_YEAR_ATTRIBUTE.equals(name)) {
			try {
				ldcDocument.setPublicationYear(Integer.parseInt(content));
			} catch (NumberFormatException e) {
				System.err.println("Unable to parse publication_year: " + content);
			}
		} else if (PULICATION_DAY_OF_WEEK_ATTRIBUTE.equals(name)) {
			ldcDocument.setDayOfWeek(content);
		} else if (SLUG_ATTRIBUTE.equals(name)) {
			ldcDocument.setSlug(content);
		}
	}

	private Document loadNonValidating(File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			return loadNonValidating(fis);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	private Document loadNonValidating(InputStream is) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);
		factory.setNamespaceAware(true);
		try {
			factory.setFeature("http://xml.org/sax/features/namespaces", false);
			factory.setFeature("http://xml.org/sax/features/validation", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(is);
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		} catch (SAXException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Document loadValidating(File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			return loadValidating(fis);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	private Document loadValidating(InputStream is) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(true);
		try {
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(is);
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		} catch (SAXException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Document parseStringToDOM(String s, String encoding, File file) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		ByteArrayInputStream bais = null;
		try {
			factory.setValidating(false);
			factory.setNamespaceAware(true);
			factory.setFeature("http://xml.org/sax/features/namespaces", false);
			factory.setFeature("http://xml.org/sax/features/validation", false);
			factory.setFeature(
					"http://apache.org/xml/features/nonvalidating/load-dtd-grammar",
					false);
			factory.setFeature(
					"http://apache.org/xml/features/nonvalidating/load-external-dtd",
					false);

			DocumentBuilder builder = factory.newDocumentBuilder();
			bais = new ByteArrayInputStream(s.getBytes(encoding));
			InputSource source = new InputSource(bais);
			if (file != null) {
			    source.setSystemId(file.toURI().toString());
			}
			return builder.parse(source);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		} catch (SAXException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		} finally {
		    if (bais != null) {
		        try {
		            bais.close();
		        } catch (IOException e) {
		            // ignore
		        }
		    }
		}
	}

	private Document getDOMObject(String filename, boolean validating)
			throws SAXException, IOException, ParserConfigurationException {
		File file = new File(filename);
		if (validating) {
			return loadValidating(file);
		} else {
			return loadNonValidating(file);
		}
	}

	private Document getDOMObjectFromInputStream(InputStream is, boolean validating)
			throws SAXException, IOException, ParserConfigurationException {
		// Create a DocumentBuilderFactory
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

	    // Configure the factory based on the 'validating' parameter
	    factory.setValidating(validating);
	    factory.setNamespaceAware(true); // Good practice

	    if (!validating) {
	        // For non-validating parsing, disable DTD loading to prevent external entity issues
	        // and improve performance if DTD is not needed.
	        try {
	            factory.setFeature("http://xml.org/sax/features/namespaces", false);
	            factory.setFeature("http://xml.org/sax/features/validation", false);
	            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
	            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
	        } catch (ParserConfigurationException e) {
	            // This should not happen with standard JAXP implementations for these features
	            System.err.println("Warning: Could not set non-validating parser features: " + e.getMessage());
	        }
	    }

	    // Create a DocumentBuilder
	    DocumentBuilder builder = factory.newDocumentBuilder();

	    // For non-validating parsers, it's often good to provide a custom EntityResolver
	    // that does nothing, to prevent attempts to download external DTDs.
	    if (!validating) {
	        builder.setEntityResolver((publicId, systemId) -> {
	            // Return an empty input source to prevent external DTD resolution
	            return new InputSource(new StringReader(""));
	        });
	    }

	    // Parse the InputStream
	    // Wrap the InputStream in an InputSource. This allows specifying encoding if known,
	    // though for XML, encoding is usually determined from the XML declaration.
	    InputSource inputSource = new InputSource(is);
	    // If encoding is known and not specified in XML, you could do: inputSource.setEncoding("UTF-8");

	    return builder.parse(inputSource);
	}

	private String parseBlock(Node node) {
		StringBuffer text = new StringBuffer();
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.TEXT_NODE) {
				text.append(child.getNodeValue());
			} else if (P_TAG.equals(child.getNodeName())) {
				text.append(getAllText(child));
				text.append("\n"); // Add newline after each paragraph
			} else {
				// Potentially other tags within block, recurse or handle as needed
				// For now, simple text aggregation from children of block (if any)
				text.append(getAllText(child));
			}
		}
		return text.toString().trim();
	}

	private String getAttributeValue(Node node, String attributeName) {
		NamedNodeMap attributes = node.getAttributes();
		if (attributes != null) {
			Node attribute = attributes.getNamedItem(attributeName);
			if (attribute != null) {
				return attribute.getNodeValue();
			}
		}
		return null;
	}

	private String getAllText(Node node) {
		StringBuffer text = new StringBuffer();
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.TEXT_NODE) {
				text.append(child.getNodeValue());
			} else if (child.getNodeType() == Node.ELEMENT_NODE) {
				// Recursively get text from element children, effectively ignoring tags
				text.append(getAllText(child));
			}
		}
		return text.toString();
	}

	private List<Node> getNodesByTagName(Node node, String tagName) {
		List<Node> matches = new ArrayList<Node>();
		recursiveGetNodesByTagName(node, tagName, matches);
		return matches;
	}

	private void recursiveGetNodesByTagName(Node node, String tagName,
			List<Node> matches) {
		if (tagName.equals(node.getNodeName())) {
			matches.add(node);
		}
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			recursiveGetNodesByTagName(children.item(i), tagName, matches);
		}
	}

	// Added for Title Tag if needed, not currently used in this flow.
	private static final String TITLE_TAG = "title";

} 