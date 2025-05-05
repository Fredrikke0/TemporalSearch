package com.example.nyt;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
	 * @param file
	 *            The file containing the document to parse
	 * @param validating
	 *            Whether or not to use validating parser
	 * @return The parsed document
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromFile(File file,
			boolean validating) {
		try {
			Document document = getDOMObject(file.getAbsolutePath(), validating);
			return parseNYTCorpusDocumentFromDOMDocument(file, document);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Added method to parse from an InputStream - Adapt logic from parseNYTCorpusDocumentFromFile and getDOMObject/parseStringToDOM
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromInputStream(InputStream is,
			boolean validating) {
		try {
			Document document = getDOMObjectFromInputStream(is, validating);
			// We pass null for File as we don't have one when reading from stream
			return parseNYTCorpusDocumentFromDOMDocument(null, document);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Parses a document from the provided DOM representation.
	 *
	 * @param file
	 *            The file from which the document was parsed (can be null)
	 * @param document
	 *            The DOM object
	 * @return The parsed document
	 */
	public NYTCorpusDocument parseNYTCorpusDocumentFromDOMDocument(
			File file, Document document) {
		NYTCorpusDocument ldcDocument = new NYTCorpusDocument();

		if (file != null) { // Added null check
			ldcDocument.setSourceFile(file);
		}

		NodeList rootList = document.getChildNodes();
		for (int i = 0; i < rootList.getLength(); i++) {
			Node node = rootList.item(i);
			if (node.getNodeName().equals(NITF_TAG)) {
				handleNITFNode(node, ldcDocument);
			}
		}
		return ldcDocument;
	}

	private void handleNITFNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList nitfList = node.getChildNodes();
		for (int i = 0; i < nitfList.getLength(); i++) {
			Node child = nitfList.item(i);
			if (child.getNodeName().equals(HEAD_TAG)) {
				handleHeadNode(child, ldcDocument);
			} else if (child.getNodeName().equals(BODY_TAG)) {
				handleBodyNode(child, ldcDocument);
			}
		}
	}

	private void handleBodyNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList bodyList = node.getChildNodes();
		for (int i = 0; i < bodyList.getLength(); i++) {
			Node child = bodyList.item(i);
			if (child.getNodeName().equals(BODY_HEAD_TAG)) {
				handleBodyHead(child, ldcDocument);
			} else if (child.getNodeName().equals(BODY_CONTENT_TAG)) {
				handleBodyContent(child, ldcDocument);
			} else if (child.getNodeName().equals(BODY_END_TAG)) {
				handleBodyEnd(child, ldcDocument);
			}
		}
	}

	private void handleBodyHead(Node node, NYTCorpusDocument ldcDocument) {
		NodeList bodyList = node.getChildNodes();
		for (int i = 0; i < bodyList.getLength(); i++) {
			Node child = bodyList.item(i);

			if (child.getNodeName().equals(HEDLINE_TAG)) {
				handleHeadlineNode(child, ldcDocument);
			} else if (child.getNodeName().equals(BYLINE_TAG)) {
				handleBylineNode(child, ldcDocument);
			} else if (child.getNodeName().equals(DATELINE_TAG)) {
				handleDatelineNode(ldcDocument, child);
			} else if (child.getNodeName().equals(ABSTRACT_TAG)) {
				handleAbstractNode(child, ldcDocument);
			}
		}
	}

	private void handleDatelineNode(NYTCorpusDocument ldcDocument, Node child) {
		ldcDocument.setDateline(getAllText(child).trim());
	}

	private void handleAbstractNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList abstractList = node.getChildNodes();
		for (int i = 0; i < abstractList.getLength(); i++) {
			Node child = abstractList.item(i);
			if (child.getNodeName().equals(P_TAG)) {
				ldcDocument.setArticleAbstract(getAllText(child).trim());
			}
		}
	}

	private void handleBylineNode(Node node, NYTCorpusDocument ldcDocument) {
		ldcDocument.setByline(getAllText(node).trim());
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node bylineClassNode = map.getNamedItem(CLASS_ATTRIBUTE);
			if (bylineClassNode != null) {
				if (bylineClassNode.getNodeValue().equals("normalized_byline")) {
					ldcDocument.setNormalizedByline(getAllText(node).trim());
				}
			}
		}
	}

	private void handleHeadlineNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList headlineList = node.getChildNodes();
		for (int i = 0; i < headlineList.getLength(); i++) {
			Node child = headlineList.item(i);
			if (child.getNodeName().equals(HL1_TAG)) {
				ldcDocument.setHeadline(getAllText(child).trim());
			} else if (child.getNodeName().equals(HL2_TAG)) {
				ldcDocument.setKicker(getAllText(child).trim());
			}
		}
	}

	private void handleBodyContent(Node node, NYTCorpusDocument ldcDocument) {
		NodeList contentList = node.getChildNodes();
		for (int i = 0; i < contentList.getLength(); i++) {
			Node child = contentList.item(i);
			if (child.getNodeName().equals(BLOCK_TAG)) {
				handleBlockNode(child, ldcDocument);
			}
		}
	}

	private void handleBlockNode(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		Node classNode = map.getNamedItem(CLASS_ATTRIBUTE);
		if (classNode.getNodeValue().equals(FULL_TEXT_ATTRIBUTE)) {
			ldcDocument.setBody(parseBlock(node).trim());
		} else if (classNode.getNodeValue().equals(LEAD_PARAGRAPH_ATTRIBUTE)) {
			ldcDocument.setLeadParagraph(parseBlock(node).trim());
		} else if (classNode.getNodeValue().equals(TAGLINE_TAG)) {
			// TODO - Do something with this...
		} else if (classNode.getNodeValue().equals(CORRECTION_TEXT)) {
			ldcDocument.setCorrectionText(parseBlock(node).trim());
		}
	}

	private void handleBodyEnd(Node node, NYTCorpusDocument ldcDocument) {
		// Nothing useful here...
	}

	private void handleHeadNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList headList = node.getChildNodes();
		for (int i = 0; i < headList.getLength(); i++) {
			Node child = headList.item(i);
			if (child.getNodeName().equals(DOCDATA_TAG)) {
				handleDocdataNode(child, ldcDocument);
			} else if (child.getNodeName().equals(PUBDATA_TAG)) {
				handlePubdata(child, ldcDocument);
			} else if (child.getNodeName().equals(META_TAG)) {
				handleMetaNode(child, ldcDocument);
			}
		}
	}

	private void handleDocdataNode(Node node, NYTCorpusDocument ldcDocument) {
		NodeList docdataList = node.getChildNodes();
		for (int i = 0; i < docdataList.getLength(); i++) {
			Node child = docdataList.item(i);
			if (child.getNodeName().equals(DOC_ID_TAG)) {
				handleDocumentIdNode(ldcDocument, child);
			} else if (child.getNodeName().equals(SERIES_TAG)) {
				handleSeriesNode(ldcDocument, child);
			} else if (child.getNodeName().equals(IDENTIFIED_CONTENT_TAG)) {
				handleIdentifiedContent(child, ldcDocument);
			}
		}
	}

	private void handlePubdata(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap attributes = node.getAttributes();
		if (attributes != null) {
			Node dateAttribute = attributes
					.getNamedItem(DATE_PUBLICATION_ATTRIBUTE);
			if (dateAttribute != null) {
				Date publicationDate;
				try {
					publicationDate = format.parse(dateAttribute.getNodeValue());
					ldcDocument.setPublicationDate(publicationDate);
				} catch (ParseException e) {
					e.printStackTrace();
				}
			}

			Node nameAttribute = attributes.getNamedItem(NAME_ATTRIBUTE);
			if (nameAttribute != null) {
				ldcDocument.setCredit(nameAttribute.getNodeValue());
			}

			Node positionAttribute = attributes.getNamedItem("position.section");
			if (positionAttribute != null) {
				ldcDocument.setSection(positionAttribute.getNodeValue());
			}

			positionAttribute = attributes.getNamedItem("position.sequence");
			if (positionAttribute != null) {
				ldcDocument.setPage(Integer.valueOf(positionAttribute
						.getNodeValue()));
			}

			Node exrefAttribute = attributes.getNamedItem(EX_REF_ATTRIBUTE);
			if (exrefAttribute != null) {
				try {
					ldcDocument.setUrl(new URL(exrefAttribute.getNodeValue()));
				} catch (MalformedURLException e) {
					e.printStackTrace();
				}
			}

			Node slugAttribute = attributes.getNamedItem(SLUG_ATTRIBUTE);
			if (slugAttribute != null) {
				ldcDocument.setSlug(slugAttribute.getNodeValue());
			}
		}
	}

	private void handleIdentifiedContent(Node node,
			NYTCorpusDocument ldcDocument) {
		NodeList contentList = node.getChildNodes();
		for (int i = 0; i < contentList.getLength(); i++) {
			Node child = contentList.item(i);
			if (child.getNodeName().equals(CLASSIFIER_TAG)) {
				handleClassifierNode(child, ldcDocument);
			} else if (child.getNodeName().equals(LOCATION_TAG)) {
				handleLocationNode(child, ldcDocument);
			} else if (child.getNodeName().equals(ORGANIZATION_TAG)) {
				handleOrganizationNode(child, ldcDocument);
			} else if (child.getNodeName().equals(PERSON_TAG)) {
				handlePersonNode(child, ldcDocument);
			} else if (child.getNodeName().equals(OBJECT_TITLE_TAG)) {
				handleObjectTitleNode(child, ldcDocument);
			}
		}
	}

	private void handleObjectTitleNode(Node node,
			NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node typeNode = map.getNamedItem(TYPE_ATTRIBUTE);
			if (typeNode != null) {
				if (typeNode.getNodeValue().equals(INDEXING_SERVICE_ATTRIBUTE)) {
					ldcDocument.getTitles().add(getAllText(node).trim());
				} else {
					ldcDocument.getOnlineTitles().add(getAllText(node).trim());
				}
			}
		}
	}

	private void handlePersonNode(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node typeNode = map.getNamedItem(TYPE_ATTRIBUTE);
			if (typeNode != null) {
				if (typeNode.getNodeValue().equals(INDEXING_SERVICE_ATTRIBUTE)) {
					ldcDocument.getPeople().add(getAllText(node).trim());
				} else {
					ldcDocument.getOnlinePeople().add(getAllText(node).trim());
				}
			}
		}
	}

	private void handleOrganizationNode(Node node,
			NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node typeNode = map.getNamedItem(TYPE_ATTRIBUTE);
			if (typeNode != null) {
				if (typeNode.getNodeValue().equals(INDEXING_SERVICE_ATTRIBUTE)) {
					ldcDocument.getOrganizations().add(getAllText(node).trim());
				} else {
					ldcDocument.getOnlineOrganizations().add(
							getAllText(node).trim());
				}
			}
		}
	}

	private void handleLocationNode(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node typeNode = map.getNamedItem(TYPE_ATTRIBUTE);
			if (typeNode != null) {
				if (typeNode.getNodeValue().equals(INDEXING_SERVICE_ATTRIBUTE)) {
					ldcDocument.getLocations().add(getAllText(node).trim());
				} else {
					ldcDocument.getOnlineLocations().add(getAllText(node).trim());
				}
			}
		}
	}

	private void handleSeriesNode(NYTCorpusDocument ldcDocument, Node child) {
		NamedNodeMap map = child.getAttributes();
		Node seriesNameNode = map.getNamedItem(SERIES_NAME_TAG);
		if (seriesNameNode != null) {
			ldcDocument.setSeriesName(seriesNameNode.getNodeValue());
		}
	}

	private void handleDocumentIdNode(NYTCorpusDocument ldcDocument, Node child) {
		NamedNodeMap map = child.getAttributes();
		Node idNode = map.getNamedItem(ID_STRING_ATTRIBUTE);
		if (idNode != null) {
			ldcDocument.setGuid(Integer.valueOf(idNode.getNodeValue()));
		}
	}

	private void handleClassifierNode(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();
		if (map != null) {
			Node typeNode = map.getNamedItem(TYPE_ATTRIBUTE);
			if (typeNode != null) {
				String type = typeNode.getNodeValue();
				if (type.equals(DESCRIPTOR_ATTRIBUTE)) {
					ldcDocument.getDescriptors().add(getAllText(node).trim());
				} else if (type.equals(TAXONOMIC_CLASSIFIER_ATTRIBUTE)) {
					String[] classifications = getAllText(node).trim().split("/");
					for (String classification : classifications) {
						if (classification.length() > 0)
							ldcDocument.getTaxonomicClassifiers().add(
									classification);
					}
				} else if (type.equals(TYPES_OF_MATERIAL_ATTRIBUTE)) {
					ldcDocument.getTypesOfMaterial().add(getAllText(node).trim());
				} else if (type.equals(GENERAL_DESCRIPTOR_ATTRIBUTE)) {
					ldcDocument.getGeneralOnlineDescriptors().add(
							getAllText(node).trim());
				} else if (type.equals(ONLINE_SECTIONS_ATTRIBUTE)) {
					ldcDocument.setOnlineSection(getAllText(node).trim());
				} else if (type.equals(BIOGRAPHICAL_CATEGORIES_ATTRIBUTE)) {
					ldcDocument.getBiographicalCategories().add(
							getAllText(node).trim());
				} else if (type.equals(NAMES_ATTRIBUTE)) {
					ldcDocument.getNames().add(getAllText(node).trim());
				}
			}
		}
	}

	private void handleMetaNode(Node node, NYTCorpusDocument ldcDocument) {
		NamedNodeMap map = node.getAttributes();

		if (map != null) {
			Node nameNode = map.getNamedItem(NAME_ATTRIBUTE);
			Node contentNode = map.getNamedItem(CONTENT_ATTRIBUTE);

			if (nameNode != null && contentNode != null) {
				String name = nameNode.getNodeValue();
				String content = contentNode.getNodeValue();

				if (name.equals(PRINT_SECTION_ATTRIBUTE)) {
					ldcDocument.setSection(content);
				} else if (name.equals(PRINT_PAGE_NUMBER_ATTRIBUTE)) {
					ldcDocument.setPage(Integer.valueOf(content));
				} else if (name.equals(CORRECTION_DATE_ATTRIBUTE)) {
					try {
						ldcDocument.setCorrectionDate(format.parse(content));
					} catch (ParseException e) {
						e.printStackTrace();
					}
				} else if (name.equals(DSK_ATTRIBUTE)) {
					ldcDocument.setNewsDesk(content);
				} else if (name.equals(ALTERNATE_URL_ATTRIBUTE)) {
					try {
						ldcDocument.setAlternateURL(new URL(content));
					} catch (MalformedURLException e) {
						e.printStackTrace();
					}
				} else if (name.equals(ONLINE_SECTIONS_ATTRIBUTE)) {
					ldcDocument.setOnlineSection(content);
				} else if (name.equals(ONLINE_HEADLINE_ATTRIBUTE)) {
					ldcDocument.setOnlineHeadline(content);
				} else if (name.equals(ONLINE_LEAD_PARAGRAPH_ATTRIBUTE)) {
					ldcDocument.setOnlineLeadParagraph(content);
				} else if (name.equals(AUTHOR_INFO_ATTRIBUTE)) {
					ldcDocument.setAuthorBiography(content);
				} else if (name.equals(BANNER_ATTRIBUTE)) {
					ldcDocument.setBanner(content);
				} else if (name.equals(FEATURE_PAGE_ATTRIBUTE)) {
					ldcDocument.setFeaturePage(content);
				} else if (name.equals(COLUMN_NAME_ATTRIBUTE)) {
					ldcDocument.setColumnName(content);
				} else if (name.equals(PRINT_COLUMN_ATTRIBUTE)) {
					ldcDocument.setColumnNumber(Integer.valueOf(content));
				} else if (name.equals(SERIES_NAME_ATTRIBUTE)) {
					ldcDocument.setSeriesName(content);
				} else if (name.equals(PRINT_BYLINE_ATTRIBUTE)) {
					ldcDocument.setByline(content);
				} else if (name.equals(NORMALIZED_BYLINE_ATTRIBUTE)) {
					ldcDocument.setNormalizedByline(content);
				} else if (name.equals(PUBLICATION_YEAR_ATTRIBUTE)) {
					ldcDocument.setPublicationYear(Integer.valueOf(content));
				} else if (name.equals(PUBLICATION_MONTH_ATTRIBUTE)) {
					ldcDocument.setPublicationMonth(Integer.valueOf(content));
				} else if (name.equals(PUBLICATION_DAY_OF_MONTH_ATTRIBUTE)) {
					ldcDocument.setPublicationDayOfMonth(Integer.valueOf(content));
				} else if (name.equals(PULICATION_DAY_OF_WEEK_ATTRIBUTE)) {
					ldcDocument.setDayOfWeek(content);
				} else if (name.equals(ONLINE_PRODUCER_ATTRIBUTE)) {
					// Do nothing...
				} else if (name.equals(ITEM_LENGTH_ATTRIBUTE)) {
					ldcDocument.setWordCount(Integer.valueOf(content));
				}
			}
		}
	}

	/**
	 * Loads a non-validating representation of the provided XML file.
	 *
	 * @param file
	 *            The XML file to load
	 * @return The DOM object for the file
	 */
	private Document loadNonValidating(File file) {
		InputStream is = null;
		try {
			is = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}

		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
		StringBuffer sb = new StringBuffer();
		try {
			while (br.ready()) {
				sb.append(br.readLine());
				sb.append('\n');
			}
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return parseStringToDOM(sb.toString(), "UTF-8", file);
	}

	/**
	 * Loads a validating representation of the provided XML file.
	 *
	 * @param file
	 *            The XML file to load
	 * @return The DOM object for the file
	 */
	private Document loadValidating(File file) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(true);
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
			Document document = builder.parse(file);
			return document;
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * Loads a non-validating XML DOM object from the provided string.
	 *
	 * @param s
	 *            The string to load
	 * @param encoding
	 *            The encoding of the string
	 * @param file
	 *            The file from which the document was parsed (can be null)
	 * @return A DOM object representing the document
	 */
	private Document parseStringToDOM(String s, String encoding, File file) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
			return null;
		}

		Document document;
		try {
			InputSource is = new InputSource(new ByteArrayInputStream(s
					.getBytes(encoding)));
			is.setEncoding(encoding);
			document = builder.parse(is);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		} catch (SAXException e) {
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return document;
	}

	/**
	 * Loads a DOM representation of the specified file.
	 *
	 * @param filename
	 *            The file to load
	 * @param validating
	 *            Whether or not to use the validating parser
	 * @return DOM Object
	 * @throws SAXException
	 * @throws IOException
	 * @throws ParserConfigurationException
	 */
	private Document getDOMObject(String filename, boolean validating)
			throws SAXException, IOException, ParserConfigurationException {
		File file = new File(filename);
		if (validating)
			return loadValidating(file);
		else
			return loadNonValidating(file);
	}

	/**
	 * Helper method to create DOM object from InputStream
	 */
	private Document getDOMObjectFromInputStream(InputStream is, boolean validating)
			throws SAXException, IOException, ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(validating); // Set validating based on parameter
		InputSource inputSource = new InputSource(is);

		// Set features BEFORE creating the builder
		try {
			// Disable external DTDs explicitly to prevent network errors (like 403)
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			// Disable external entities
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);

		} catch (ParserConfigurationException e) {
			// Log or handle more gracefully if needed
			System.err.println("Warning: Could not set secure XML processing features: " + e.getMessage());
		}

		// Create the builder AFTER setting factory features
		DocumentBuilder builder = factory.newDocumentBuilder();

		// Optional: Set an entity resolver that does nothing or provides local DTDs
		// This can be an alternative if feature flags fail.
		// builder.setEntityResolver(new org.xml.sax.EntityResolver() {
		//     @Override
		//     public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
		//         // Return an empty input source to prevent network access
		//         return new InputSource(new java.io.StringReader(""));
		//     }
		// });

		return builder.parse(inputSource);
	}

	/**
	 * Parses the text content from within a Block node
	 *
	 * @param node
	 * @return
	 */
	private String parseBlock(Node node) {
		StringBuffer sb = new StringBuffer();
		NodeList pList = node.getChildNodes();
		for (int i = 0; i < pList.getLength(); i++) {
			Node child = pList.item(i);
			if (child.getNodeName().equals(P_TAG)) {
				sb.append(getAllText(child));
				sb.append("\n");
			}
		}

		return sb.toString();
	}

	/**
	 * Returns the value of the specified attribute for the given node
	 *
	 * @param node
	 * @param attributeName
	 * @return
	 */
	private String getAttributeValue(Node node, String attributeName) {
		NamedNodeMap map = node.getAttributes();
		Node attribute = map.getNamedItem(attributeName);
		if (attribute != null) {
			return attribute.getNodeValue();
		} else {
			return null;
		}
	}

	/**
	 * Returns all text content contained within the specified node
	 *
	 * @param node
	 * @return
	 */
	private String getAllText(Node node) {
		if (node.getNodeType() == Node.TEXT_NODE) {
			return node.getNodeValue();
		} else {
			StringBuffer sb = new StringBuffer();
			NodeList children = node.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				sb.append(getAllText(children.item(i)));
			}
			return sb.toString();
		}
	}

	/**
	 * Returns a list of nodes with the specified tag name
	 *
	 * @param node
	 * @param tagName
	 * @return
	 */
	private List<Node> getNodesByTagName(Node node, String tagName) {
		List<Node> matches = new ArrayList<Node>();
		recursiveGetNodesByTagName(node, tagName, matches);
		return matches;
	}

	/**
	 * Recursive helper method for getNodesByTagName
	 *
	 * @param node
	 * @param tagName
	 * @param matches
	 */
	private void recursiveGetNodesByTagName(Node node, String tagName,
			List<Node> matches) {
		if (node.getNodeName().equals(tagName)) {
			matches.add(node);
		}

		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			recursiveGetNodesByTagName(children.item(i), tagName, matches);
		}
	}
} 