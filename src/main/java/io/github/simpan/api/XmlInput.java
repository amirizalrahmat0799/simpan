package io.github.simpan.api;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Parses request bodies (DeleteObjects, CompleteMultipartUpload, CreateBucketConfiguration) with DTDs and external
 * entities disabled, so a hostile body cannot read local files or expand into gigabytes (XXE, billion laughs).
 */
public final class XmlInput {

    private XmlInput() {}

    public static Element parse(byte[] body) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Document d = b.parse(new ByteArrayInputStream(body));
            return d.getDocumentElement();
        } catch (Exception e) {
            throw new S3Exception(S3Error.MALFORMED_XML);
        }
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    public static String text(Element parent, String localName) {
        List<Element> c = children(parent, localName);
        return c.isEmpty() ? null : c.get(0).getTextContent();
    }

    public static String name(Element e) {
        return e.getLocalName();
    }
}
