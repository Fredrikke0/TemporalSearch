package com.example.nyt;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * NYTimesLDCDocument <BR>
 * Created: Jun 17, 2008 <BR>
 * Author: Evan Sandhaus (sandhes@nytimes.com)<BR>
 * <P>
 * This class represents a New York Times Corpus Document. See field comments
 * for individual field description.
 * <P>
 *
 * @author Evan Sandhaus
 *
 */
public class NYTCorpusDocument {
	/**
	 * This field specifies the location on nytimes.com of the article. When
	 * present, this URL is preferred to the URL field on articles published on
	 * or after April 02, 2006, as the linked page will have richer content.
	 */
	protected URL alternateURL;

	/**
	 * This field is a summary of the article written by the New York Times
	 * Indexing Service.
	 */
	protected String articleAbstract;

	/**
	 * This field specifies the biography of the author of the article.
	 * Generally, this field is specified for guest authors not for New York
	 * Times reporters. When this field is specified for Times reporters, it is
	 * usually used to provide the author's email address.
	 */
	protected String authorBiography;

	/**
	 * The banner field is used to indicate if there has been additional
	 * information appended to the articles since its publication. Examples of
	 * banners include ('Correction Appended' and 'Editor's Note Appended').
	 */
	protected String banner;

	/**
	 * When present, the biographical category field generally indicates that a
	 * document focuses on a particular individual. The value of the field
	 * indicates the area or category in which this individual is best known.
	 * This field is most often defined for Obituaries and Book Reviews. These
	 * tags are hand-assigned by a team of library scientists working for the
	 * New York Times Indexing service.
	 *
	 * <ol>
	 * <li>Politics and Government (U.S.) <li>Books and Magazines <li>Royalty
	 * </ol>
	 */
	protected List<String> biographicalCategories = new ArrayList<String>();

	/**
	 * The body field is the text content of the article. Please note that this
	 * value includes the lead paragraph.
	 */
	protected String body;

	/**
	 * This field specifies the byline of the article as it appeared in the
	 * print edition of the New York Times. Please note that not every article
	 * in this collection has a byline, as editorials and other types of
	 * articles are generally unsigned.
	 * <P>
	 * Sample byline:
	 * <ul>
	 * <li>By James Reston
	 * <li>By JAMES GLANZ; William J. Broad contributed reporting for this
	 * article.
	 * <li>By ADAM NAGOURNEY and JEFF ZELENY
	 * </ul>
	 */
	protected String byline;

	/**
	 * If the article is part of a regular column, this field specifies the name
	 * of that column.
	 * <p>
	 * Sample Column Names:
	 * <p>
	 * <ol>
	 * <li>World News Briefs
	 * <li>WEDDINGS
	 * <li>The Accessories Channel
	 * </ol>
	 *
	 */
	protected String columnName;

	/**
	 * This field specifies the column in which the article starts in the print
	 * paper. A typical printed page in the paper has six columns numbered from
	 * right to left. As a consequence most, but not all, of the values for this
	 * field fall in the range 1-6.
	 */
	protected Integer columnNumber;

	/**
	 * This field specifies the date on which a correction was made to the
	 * article. Generally, if the correction date is specified, the correction
	 * text will also be specified (and vice versa).
	 */
	protected Date correctionDate;

	/**
	 * For articles corrected following publication, this field specifies the
	 * correction. Generally, if the correction text is specified, the
	 * correction date will also be specified (and vice versa).
	 */
	protected String correctionText;

	/**
	 * This field indicates the entity that produced the editorial content of
	 * this document. For this collection, the credit will always be set to 'The
	 * New York Times'.
	 */
	protected String credit;

	/**
	 * The �dateline� field is the dateline of the article. Generally a dateline
	 * is the name of the geographic location from which the article was filed
	 * followed by a comma and the month and day of the filing.
	 * <p>
	 * Sample datelines:
	 * <ul>
	 * <li>WASHINGTON, April 30
	 * <li>RIYADH, Saudi Arabia, March 29
	 * <li>ONTARIO, N.Y., Jan. 26
	 * </ul>
	 * Please note:
	 * <ol>
	 * <li>The dateline location is the location from which the article was
	 * filed. Often times this location is related to the content of the
	 * article, but this is not guaranteed.
	 * <li>The date specified for the dateline is often but not always the day
	 * previous to the publication date.
	 * <li>The date is usually but not always specified.
	 * </ol>
	 */
	protected String dateline;

	/**
	 * This field specifies the day of week on which the article was published.
	 * <ul>
	 * <li>Monday <li>Tuesday <li>Wednesday <li>Thursday <li>Friday <li>Saturday
	 * <li>Sunday
	 * </ul>
	 */
	protected String dayOfWeek;

	/**
	 * The �descriptors� field specifies a list of descriptive terms drawn from
	 * a normalized controlled vocabulary corresponding to subjects mentioned in
	 * the article. These tags are hand-assigned by a team of library scientists
	 * working in the New York Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>ECONOMIC CONDITIONS AND TRENDS
	 * <li>AIRPLANES
	 * <li>VIOLINS
	 * </ol>
	 */
	protected List<String> descriptors = new ArrayList<String>();

	/**
	 * The
	 */
	protected String featurePage;

	/**
	 * The �general online descriptors� field specifies a list of descriptors
	 * that are at a higher level of generality than the other tags associated
	 * with the article. These tags are algorithmically assigned and manually
	 * verified by nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Surfing
	 * <li>Venice Biennale
	 * <li>Ranches
	 * </ol>
	 */
	protected List<String> generalOnlineDescriptors = new ArrayList<String>();

	/**
	 * The GUID field specifies a an integer that is guaranteed to be unique for
	 * every document in the corpus.
	 */
	protected int guid;

	/**
	 * This field specifies the headline of the article as it appeared in the
	 * print edition of the New York Times.
	 */
	protected String headline;

	/**
	 * The kicker is an additional piece of information printed as an
	 * accompaniment to a news headline.
	 */
	protected String kicker;

	/**
	 * The �lead Paragraph� field is the lead paragraph of the article.
	 * Generally this field is populated with the first two paragraphs from the
	 * article.
	 */
	protected String leadParagraph;

	/**
	 * The �locations� field specifies a list of geographic descriptors drawn
	 * from a normalized controlled vocabulary that correspond to places
	 * mentioned in the article. These tags are hand-assigned by a team of
	 * library scientists working for the New York Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Wellsboro (Pa)
	 * <li>Kansas City (Kan)
	 * <li>Park Slope (NYC)
	 * </ol>
	 */
	protected List<String> locations = new ArrayList<String>();

	/**
	 * The �names� field specifies a list of proper nouns corresponding to
	 * subjects mentioned in the article that are not otherwise categorized as
	 * people, places, or organizations. This field is generally used to capture
	 * names of works of art, legislation, court cases etc. These tags are
	 * hand-assigned by a team of library scientists working in the New York
	 * Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>STAR WARS (MOVIE)
	 * <li>TASTE OF THE NATION
	 * <li>NO CHILD LEFT BEHIND ACT (2001)
	 * </ol>
	 */
	protected List<String> names = new ArrayList<String>();

	/**
	 * The �news desk� field is the section of the New York Times newspaper
	 * organization responsible for the article. This field applies primarily to
	 * news articles.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Foreign
	 * <li>Business
	 * <li>National
	 * </ol>
	 */
	protected String newsDesk;

	/**
	 * This field specifies a normalized version of the byline. Generally, this
	 * normalization consists of converting the byline to uppercase and removing
	 * prefixes such as 'By'.
	 * <p>
	 * Sample Normalized Bylines:
	 * <p>
	 * <ul>
	 * <li>JAMES RESTON
	 * <li>JAMES GLANZ; WILLIAM J. BROAD CONTRIBUTED REPORTING FOR THIS ARTICLE.
	 * <li>ADAM NAGOURNEY AND JEFF ZELENY
	 * </ul>
	 */
	protected String normalizedByline;

	/**
	 * The �online descriptors� field specifies a list of descriptive terms
	 * corresponding to subjects mentioned in the article that is geared towards
	 * publication on the nytimes.com website. These tags are algorithmically
	 * assigned and manually verified by nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Baseball
	 * <li>Terrorism
	 * <li>Flags
	 * </ol>
	 */
	protected List<String> onlineDescriptors = new ArrayList<String>();

	/**
	 * This field specifies the headline of the article as it appeared on the
	 * nytimes.com website.
	 */
	protected String onlineHeadline;

	/**
	 * The online lead paragraph field is the lead paragraph of the article as it
	 * appeared on the nytimes.com website.
	 */
	protected String onlineLeadParagraph;

	/**
	 * The �online locations� field specifies a list of geographic descriptors
	 * corresponding to places mentioned in the article that is geared towards
	 * publication on the nytimes.com website. These tags are algorithmically
	 * assigned and manually verified by nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Washington (DC)
	 * <li>Florida Keys (Fla)
	 * <li>San Joaquin Valley (Calif)
	 * </ol>
	 */
	protected List<String> onlineLocations = new ArrayList<String>();

	/**
	 * The �online organizations� field specifies a list of organizations
	 * mentioned in the article that is geared towards publication on the
	 * nytimes.com website. These tags are algorithmically assigned and manually
	 * verified by nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>TIAA-CREF
	 * <li>Wal-Mart Stores Inc
	 * <li>Republican Party
	 * </ol>
	 */
	protected List<String> onlineOrganizations = new ArrayList<String>();

	/**
	 * The �online people� field specifies a list of people mentioned in the
	 * article that is geared towards publication on the nytimes.com website.
	 * These tags are algorithmically assigned and manually verified by
	 * nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Ashcroft, John D
	 * <li>Clemens, Roger
	 * <li>Rice, Condoleezza
	 * </ol>
	 */
	protected List<String> onlinePeople = new ArrayList<String>();

	/**
	 * This field specifies the section on the nytimes.com website under which
	 * the article appeared.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Health&gt;Fitness & Nutrition
	 * <li>Arts&gt;Art & Design
	 * <li>Sports&gt;Pro Football
	 * </ol>
	 */
	protected String onlineSection;

	/**
	 * The �online titles� field specifies a list of titles mentioned in the
	 * article that is geared towards publication on the nytimes.com website.
	 * This field is generally used to capture titles of works of art,
	 * legislation, court cases etc. These tags are algorithmically assigned and
	 * manually verified by nytimes.com production staff.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>Harry Potter and the Deathly Hallows (Book)
	 * <li>Rush Hour 3 (Movie)
	 * <li>Hamlet (Play)
	 * </ol>
	 */
	protected List<String> onlineTitles = new ArrayList<String>();

	/**
	 * The �organizations� field specifies a list of descriptors drawn from a
	 * normalized controlled vocabulary corresponding to organizations mentioned
	 * in the article. These tags are hand-assigned by a team of library
	 * scientists working for the New York Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>National Football League
	 * <li>Starbucks Corporation
	 * <li>Metropolitan Opera
	 * </ol>
	 */
	protected List<String> organizations = new ArrayList<String>();

	/**
	 * This field specifies the page on which the article starts in the print
	 * paper.
	 */
	protected Integer page;

	/**
	 * The �people� field specifies a list of descriptors drawn from a normalized
	 * controlled vocabulary corresponding to people mentioned in the article.
	 * These tags are hand-assigned by a team of library scientists working for
	 * the New York Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>OBAMA, BARACK
	 * <li>BUSH, GEORGE W
	 * <li>CLINTON, HILLARY RODHAM
	 * </ol>
	 */
	protected List<String> people = new ArrayList<String>();

	/**
	 * This field specifies the date on which the article was published.
	 */
	protected Date publicationDate;

	/**
	 * This field specifies the day of the month on which the article was
	 * published.
	 */
	protected Integer publicationDayOfMonth;

	/**
	 * This field specifies the month in which the article was published.
	 */
	protected Integer publicationMonth;

	/**
	 * This field specifies the year in which the article was published.
	 */
	protected Integer publicationYear;

	/**
	 * This field specifies the section in which the article was published in the
	 * print edition.
	 */
	protected String section;

	/**
	 * If the article is part of a regular series, this field specifies the name
	 * of that series.
	 */
	protected String seriesName;

	/**
	 * The slug is a short string that is used internally by the New York Times
	 * news desk to identify the article.
	 */
	protected String slug;

	/** This field is added by the parser and contains the source file. */
	protected File sourceFile;

	/**
	 * The taxonomic classifiers field specifies a hierarchy of categories that
	 * characterize the document. These are hand-assigned by a team of library
	 * scientists working in the New York Times Indexing service. The first line
	 * of the taxonomic classifier represents the most general category and
	 * subsequent lines represent successively more specific categories.
	 * <p>
	 * Sample Taxonomic Classifiers:
	 * <ul>
	 * <li>Top/News/Sports/Pro Basketball
	 * <li>Top/Features/Travel/Guides/Destinations/North America/United
	 * States/New York/New York City
	 * <li>Top/Opinion
	 * </ul>
	 */
	protected List<String> taxonomicClassifiers = new ArrayList<String>();

	/**
	 * The �titles� field specifies a list of titles mentioned in the article
	 * that is geared towards publication on the nytimes.com website. This field
	 * is generally used to capture titles of works of art, legislation, court
	 * cases etc. These tags are hand-assigned by a team of library scientists
	 * working for the New York Times Indexing service.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>HARRY POTTER AND THE DEATHLY HALLOWS (BOOK)
	 * <li>RUSH HOUR 3 (MOVIE)
	 * <li>HAMLET (PLAY)
	 * </ol>
	 */
	protected List<String> titles = new ArrayList<String>();

	/**
	 * The �types of material� field specifies the general category into which
	 * the document falls.
	 * <p>
	 * Examples Include:
	 * <ol>
	 * <li>News
	 * <li>Op-Ed
	 * <li>Letter
	 * </ol>
	 */
	protected List<String> typesOfMaterial = new ArrayList<String>();

	/**
	 * This field specifies the location on nytimes.com of the article. This URL
	 * should be used for all articles published before April 02, 2006.
	 */
	protected URL url;

	/**
	 * The number of words in the article.
	 */
	protected Integer wordCount;

	/**
	 * @return the alternateURL
	 */
	public URL getAlternateURL() {
		return alternateURL;
	}

	/**
	 * @return the articleAbstract
	 */
	public String getArticleAbstract() {
		return articleAbstract;
	}

	/**
	 * @return the authorBiography
	 */
	public String getAuthorBiography() {
		return authorBiography;
	}

	/**
	 * @return the banner
	 */
	public String getBanner() {
		return banner;
	}

	/**
	 * @return the biographicalCategories
	 */
	public List<String> getBiographicalCategories() {
		return biographicalCategories;
	}

	/**
	 * @return the body
	 */
	public String getBody() {
		return body;
	}

	/**
	 * @return the byline
	 */
	public String getByline() {
		return byline;
	}

	/**
	 * @return the columnName
	 */
	public String getColumnName() {
		return columnName;
	}

	/**
	 * @return the columnNumber
	 */
	public Integer getColumnNumber() {
		return columnNumber;
	}

	/**
	 * @return the correctionDate
	 */
	public Date getCorrectionDate() {
		return correctionDate;
	}

	/**
	 * @return the correctionText
	 */
	public String getCorrectionText() {
		return correctionText;
	}

	/**
	 * @return the credit
	 */
	public String getCredit() {
		return credit;
	}

	/**
	 * @return the dateline
	 */
	public String getDateline() {
		return dateline;
	}

	/**
	 * @return the dayOfWeek
	 */
	public String getDayOfWeek() {
		return dayOfWeek;
	}

	/**
	 * @return the descriptors
	 */
	public List<String> getDescriptors() {
		return descriptors;
	}

	/**
	 * @return the featurePage
	 */
	public String getFeaturePage() {
		return featurePage;
	}

	/**
	 * @return the generalOnlineDescriptors
	 */
	public List<String> getGeneralOnlineDescriptors() {
		return generalOnlineDescriptors;
	}

	/**
	 * @return the guid
	 */
	public int getGuid() {
		return guid;
	}

	/**
	 * @return the headline
	 */
	public String getHeadline() {
		return headline;
	}

	/**
	 * @return the kicker
	 */
	public String getKicker() {
		return kicker;
	}

	/**
	 * @return the leadParagraph
	 */
	public String getLeadParagraph() {
		return leadParagraph;
	}

	/**
	 * @return the locations
	 */
	public List<String> getLocations() {
		return locations;
	}

	/**
	 * @return the names
	 */
	public List<String> getNames() {
		return names;
	}

	/**
	 * @return the newsDesk
	 */
	public String getNewsDesk() {
		return newsDesk;
	}

	/**
	 * @return the normalizedByline
	 */
	public String getNormalizedByline() {
		return normalizedByline;
	}

	/**
	 * @return the onlineDescriptors
	 */
	public List<String> getOnlineDescriptors() {
		return onlineDescriptors;
	}

	/**
	 * @return the onlineHeadline
	 */
	public String getOnlineHeadline() {
		return onlineHeadline;
	}

	/**
	 * @return the onlineLeadParagraph
	 */
	public String getOnlineLeadParagraph() {
		return onlineLeadParagraph;
	}

	/**
	 * @return the onlineLocations
	 */
	public List<String> getOnlineLocations() {
		return onlineLocations;
	}

	/**
	 * @return the onlineOrganizations
	 */
	public List<String> getOnlineOrganizations() {
		return onlineOrganizations;
	}

	/**
	 * @return the onlinePeople
	 */
	public List<String> getOnlinePeople() {
		return onlinePeople;
	}

	/**
	 * @return the onlineSection
	 */
	public String getOnlineSection() {
		return onlineSection;
	}

	/**
	 * @return the onlineTitles
	 */
	public List<String> getOnlineTitles() {
		return onlineTitles;
	}

	/**
	 * @return the organizations
	 */
	public List<String> getOrganizations() {
		return organizations;
	}

	/**
	 * @return the page
	 */
	public Integer getPage() {
		return page;
	}

	/**
	 * @return the people
	 */
	public List<String> getPeople() {
		return people;
	}

	/**
	 * @return the publicationDate
	 */
	public Date getPublicationDate() {
		return publicationDate;
	}

	/**
	 * @return the publicationDayOfMonth
	 */
	public Integer getPublicationDayOfMonth() {
		return publicationDayOfMonth;
	}

	/**
	 * @return the publicationMonth
	 */
	public Integer getPublicationMonth() {
		return publicationMonth;
	}

	/**
	 * @return the publicationYear
	 */
	public Integer getPublicationYear() {
		return publicationYear;
	}

	/**
	 * @return the section
	 */
	public String getSection() {
		return section;
	}

	/**
	 * @return the seriesName
	 */
	public String getSeriesName() {
		return seriesName;
	}

	/**
	 * @return the slug
	 */
	public String getSlug() {
		return slug;
	}

	/**
	 * @return the sourceFile
	 */
	public File getSourceFile() {
		return sourceFile;
	}

	/**
	 * @return the taxonomicClassifiers
	 */
	public List<String> getTaxonomicClassifiers() {
		return taxonomicClassifiers;
	}

	/**
	 * @return the titles
	 */
	public List<String> getTitles() {
		return titles;
	}

	/**
	 * @return the typesOfMaterial
	 */
	public List<String> getTypesOfMaterial() {
		return typesOfMaterial;
	}

	/**
	 * @return the url
	 */
	public URL getUrl() {
		return url;
	}

	/**
	 * @return the wordCount
	 */
	public Integer getWordCount() {
		return wordCount;
	}

	/**
	 * Pads a string on the right with spaces to the desired length.
	 *
	 * @param s String to pad.
	 * @param length Target length.
	 * @return Padded string.
	 */
	private String ljust(String s, Integer length) {
	    if (s == null) {
	        s = ""; // Handle null input string gracefully
	    }
	    if (length == null || s.length() >= length) {
	        return s; // Return original string if length is null or already sufficient
	    }
	    StringBuilder sb = new StringBuilder(s);
	    while (sb.length() < length) {
	        sb.append(' ');
	    }
	    return sb.toString();
	}

	/**
	 * @param alternateURL the alternateURL to set
	 */
	public void setAlternateURL(URL alternateURL) {
		this.alternateURL = alternateURL;
	}

	/**
	 * @param articleAbstract the articleAbstract to set
	 */
	public void setArticleAbstract(String articleAbstract) {
		this.articleAbstract = articleAbstract;
	}

	/**
	 * @param authorBiography the authorBiography to set
	 */
	public void setAuthorBiography(String authorBiography) {
		this.authorBiography = authorBiography;
	}

	/**
	 * @param banner the banner to set
	 */
	public void setBanner(String banner) {
		this.banner = banner;
	}

	/**
	 * @param biographicalCategories the biographicalCategories to set
	 */
	public void setBiographicalCategories(List<String> biographicalCategories) {
		this.biographicalCategories = biographicalCategories;
	}

	/**
	 * @param body the body to set
	 */
	public void setBody(String body) {
		this.body = body;
	}

	/**
	 * @param byline the byline to set
	 */
	public void setByline(String byline) {
		this.byline = byline;
	}

	/**
	 * @param columnName the columnName to set
	 */
	public void setColumnName(String columnName) {
		this.columnName = columnName;
	}

	/**
	 * @param columnNumber the columnNumber to set
	 */
	public void setColumnNumber(Integer columnNumber) {
		this.columnNumber = columnNumber;
	}

	/**
	 * @param correctionDate the correctionDate to set
	 */
	public void setCorrectionDate(Date correctionDate) {
		this.correctionDate = correctionDate;
	}

	/**
	 * @param correctionText the correctionText to set
	 */
	public void setCorrectionText(String correctionText) {
		this.correctionText = correctionText;
	}

	/**
	 * @param credit the credit to set
	 */
	public void setCredit(String credit) {
		this.credit = credit;
	}

	/**
	 * @param dateline the dateline to set
	 */
	public void setDateline(String dateline) {
		this.dateline = dateline;
	}

	/**
	 * @param dayOfWeek the dayOfWeek to set
	 */
	public void setDayOfWeek(String dayOfWeek) {
		this.dayOfWeek = dayOfWeek;
	}

	/**
	 * @param descriptors the descriptors to set
	 */
	public void setDescriptors(List<String> descriptors) {
		this.descriptors = descriptors;
	}

	/**
	 * @param featurePage the featurePage to set
	 */
	public void setFeaturePage(String featurePage) {
		this.featurePage = featurePage;
	}

	/**
	 * @param generalOnlineDescriptors the generalOnlineDescriptors to set
	 */
	public void setGeneralOnlineDescriptors(
			List<String> generalOnlineDescriptors) {
		this.generalOnlineDescriptors = generalOnlineDescriptors;
	}

	/**
	 * @param guid the guid to set
	 */
	public void setGuid(int guid) {
		this.guid = guid;
	}

	/**
	 * @param headline the headline to set
	 */
	public void setHeadline(String headline) {
		this.headline = headline;
	}

	/**
	 * @param kicker the kicker to set
	 */
	public void setKicker(String kicker) {
		this.kicker = kicker;
	}

	/**
	 * @param leadParagraph the leadParagraph to set
	 */
	public void setLeadParagraph(String leadParagraph) {
		this.leadParagraph = leadParagraph;
	}

	/**
	 * @param locations the locations to set
	 */
	public void setLocations(List<String> locations) {
		this.locations = locations;
	}

	/**
	 * @param names the names to set
	 */
	public void setNames(List<String> names) {
		this.names = names;
	}

	/**
	 * @param newsDesk the newsDesk to set
	 */
	public void setNewsDesk(String newsDesk) {
		this.newsDesk = newsDesk;
	}

	/**
	 * @param normalizedByline the normalizedByline to set
	 */
	public void setNormalizedByline(String normalizedByline) {
		this.normalizedByline = normalizedByline;
	}

	/**
	 * @param onlineDescriptors the onlineDescriptors to set
	 */
	public void setOnlineDescriptors(List<String> onlineDescriptors) {
		this.onlineDescriptors = onlineDescriptors;
	}

	/**
	 * @param onlineHeadline the onlineHeadline to set
	 */
	public void setOnlineHeadline(String onlineHeadline) {
		this.onlineHeadline = onlineHeadline;
	}

	/**
	 * @param onlineLeadParagraph the onlineLeadParagraph to set
	 */
	public void setOnlineLeadParagraph(String onlineLeadParagraph) {
		this.onlineLeadParagraph = onlineLeadParagraph;
	}

	/**
	 * @param onlineLocations the onlineLocations to set
	 */
	public void setOnlineLocations(List<String> onlineLocations) {
		this.onlineLocations = onlineLocations;
	}

	/**
	 * @param onlineOrganizations the onlineOrganizations to set
	 */
	public void setOnlineOrganizations(List<String> onlineOrganizations) {
		this.onlineOrganizations = onlineOrganizations;
	}

	/**
	 * @param onlinePeople the onlinePeople to set
	 */
	public void setOnlinePeople(List<String> onlinePeople) {
		this.onlinePeople = onlinePeople;
	}

	/**
	 * @param onlineSection the onlineSection to set
	 */
	public void setOnlineSection(String onlineSection) {
		this.onlineSection = onlineSection;
	}

	/**
	 * @param onlineTitles the onlineTitles to set
	 */
	public void setOnlineTitles(List<String> onlineTitles) {
		this.onlineTitles = onlineTitles;
	}

	/**
	 * @param organizations the organizations to set
	 */
	public void setOrganizations(List<String> organizations) {
		this.organizations = organizations;
	}

	/**
	 * @param page the page to set
	 */
	public void setPage(Integer page) {
		this.page = page;
	}

	/**
	 * @param people the people to set
	 */
	public void setPeople(List<String> people) {
		this.people = people;
	}

	/**
	 * @param publicationDate the publicationDate to set
	 */
	public void setPublicationDate(Date publicationDate) {
		this.publicationDate = publicationDate;
	}

	/**
	 * @param publicationDayOfMonth the publicationDayOfMonth to set
	 */
	public void setPublicationDayOfMonth(Integer publicationDayOfMonth) {
		this.publicationDayOfMonth = publicationDayOfMonth;
	}

	/**
	 * @param publicationMonth the publicationMonth to set
	 */
	public void setPublicationMonth(Integer publicationMonth) {
		this.publicationMonth = publicationMonth;
	}

	/**
	 * @param publicationYear the publicationYear to set
	 */
	public void setPublicationYear(Integer publicationYear) {
		this.publicationYear = publicationYear;
	}

	/**
	 * @param section the section to set
	 */
	public void setSection(String section) {
		this.section = section;
	}

	/**
	 * @param seriesName the seriesName to set
	 */
	public void setSeriesName(String seriesName) {
		this.seriesName = seriesName;
	}

	/**
	 * @param slug the slug to set
	 */
	public void setSlug(String slug) {
		this.slug = slug;
	}

	/**
	 * @param sourceFile the sourceFile to set
	 */
	public void setSourceFile(File sourceFile) {
		this.sourceFile = sourceFile;
	}

	/**
	 * @param taxonomicClassifiers the taxonomicClassifiers to set
	 */
	public void setTaxonomicClassifiers(List<String> taxonomicClassifiers) {
		this.taxonomicClassifiers = taxonomicClassifiers;
	}

	/**
	 * @param titles the titles to set
	 */
	public void setTitles(List<String> titles) {
		this.titles = titles;
	}

	/**
	 * @param typesOfMaterial the typesOfMaterial to set
	 */
	public void setTypesOfMaterial(List<String> typesOfMaterial) {
		this.typesOfMaterial = typesOfMaterial;
	}

	/**
	 * @param url the url to set
	 */
	public void setUrl(URL url) {
		this.url = url;
	}

	/**
	 * @param wordCount the wordCount to set
	 */
	public void setWordCount(Integer wordCount) {
		this.wordCount = wordCount;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		appendProperty(sb, "GUID", this.guid);
		appendProperty(sb, "Source File", this.sourceFile);
		appendProperty(sb, "Headline", this.headline);
		appendProperty(sb, "Byline", this.byline);
		appendProperty(sb, "Dateline", this.dateline);
		appendProperty(sb, "Publication Date", this.publicationDate);
		appendProperty(sb, "Word Count", this.wordCount);
		appendProperty(sb, "Alternate URL", this.alternateURL);
		appendProperty(sb, "Article Abstract", this.articleAbstract);
		appendProperty(sb, "Author Biography", this.authorBiography);
		appendProperty(sb, "Banner", this.banner);
		appendProperty(sb, "Biographical Categories", this.biographicalCategories);
		appendProperty(sb, "Body", this.body);
		appendProperty(sb, "Column Name", this.columnName);
		appendProperty(sb, "Column Number", this.columnNumber);
		appendProperty(sb, "Correction Date", this.correctionDate);
		appendProperty(sb, "Correction Text", this.correctionText);
		appendProperty(sb, "Credit", this.credit);
		appendProperty(sb, "Day of Week", this.dayOfWeek);
		appendProperty(sb, "Descriptors", this.descriptors);
		appendProperty(sb, "Feature Page", this.featurePage);
		appendProperty(sb, "General Online Descriptors",
				this.generalOnlineDescriptors);
		appendProperty(sb, "Kicker", this.kicker);
		appendProperty(sb, "Lead Paragraph", this.leadParagraph);
		appendProperty(sb, "Locations", this.locations);
		appendProperty(sb, "Names", this.names);
		appendProperty(sb, "News Desk", this.newsDesk);
		appendProperty(sb, "Normalized Byline", this.normalizedByline);
		appendProperty(sb, "Online Descriptors", this.onlineDescriptors);
		appendProperty(sb, "Online Headline", this.onlineHeadline);
		appendProperty(sb, "Online Lead Paragraph", this.onlineLeadParagraph);
		appendProperty(sb, "Online Locations", this.onlineLocations);
		appendProperty(sb, "Online Organizations", this.onlineOrganizations);
		appendProperty(sb, "Online People", this.onlinePeople);
		appendProperty(sb, "Online Section", this.onlineSection);
		appendProperty(sb, "Online Titles", this.onlineTitles);
		appendProperty(sb, "Organizations", this.organizations);
		appendProperty(sb, "Page", this.page);
		appendProperty(sb, "People", this.people);
		appendProperty(sb, "Publication Day Of Month",
				this.publicationDayOfMonth);
		appendProperty(sb, "Publication Month", this.publicationMonth);
		appendProperty(sb, "Publication Year", this.publicationYear);
		appendProperty(sb, "Section", this.section);
		appendProperty(sb, "Series Name", this.seriesName);
		appendProperty(sb, "Slug", this.slug);
		appendProperty(sb, "Taxonomic Classifiers", this.taxonomicClassifiers);
		appendProperty(sb, "Titles", this.titles);
		appendProperty(sb, "Types of Material", this.typesOfMaterial);
		appendProperty(sb, "URL", this.url);

		return sb.toString();
	}

	private void appendProperty(StringBuffer sb, String propertyName,
			Object propertyValue) {
		sb.append(ljust(propertyName, 30));
		sb.append(": ");
		if (propertyValue != null) {
			sb.append(propertyValue.toString());
		}
		sb.append("\n");
	}

} 