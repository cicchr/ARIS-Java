package edu.rpi.aris.proof;

import edu.rpi.aris.LibAris;
import edu.rpi.aris.assign.Problem;
import edu.rpi.aris.assign.ProblemConverter;
import edu.rpi.aris.ast.Expression;
import edu.rpi.aris.rules.RuleList;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class SaveManager implements ProblemConverter<LibAris> {

    public static final String FILE_EXTENSION = "bram";
    public static final String FITCH_FILE_EXT = "prf";
    private final SaveInfoListener listener;
    private DocumentBuilder documentBuilder;
    private Transformer transformer;
    private XMLReader xmlReader;
    private MessageDigest hash;

    public SaveManager(SaveInfoListener listener) {
        Objects.requireNonNull(listener);
        this.listener = listener;
        try {
            documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            transformer = transformerFactory.newTransformer();
            hash = MessageDigest.getInstance("SHA-256");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            // create a SAX parser factor to be used for parsing the XML
            // we are using this instead of the default transformer to prevent xml xxe exploits
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            xmlReader = spf.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | TransformerConfigurationException | NoSuchAlgorithmException | SAXException e) {
            throw new RuntimeException("Failed to initialize file saving", e);
        }
    }

    private static Element getElementByTag(Element parent, String tag) throws IOException {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() != 1 || !(list.item(0) instanceof Element))
            throw new IOException("Invalid file format");
        return (Element) list.item(0);
    }

    private static ArrayList<Element> getElementsByTag(Element parent, String tag) throws IOException {
        NodeList list = parent.getElementsByTagName(tag);
        ArrayList<Element> elements = new ArrayList<>();
        for (int i = 0; i < list.getLength(); ++i) {
            if (!(list.item(i) instanceof Element))
                throw new IOException("Invalid file format");
            elements.add((Element) list.item(i));
        }
        return elements;
    }

    public synchronized boolean saveProof(Proof proof, File file) throws IOException, TransformerException {
        return saveProof(proof, file, true);
    }

    public synchronized boolean saveProof(Proof proof, File file, boolean saveAuthors) throws IOException, TransformerException {
        if (proof == null || file == null)
            return false;
        if (!file.exists())
            if (!file.createNewFile())
                throw new IOException("Failed to save proof");
        return saveProof(proof, new FileOutputStream(file), saveAuthors);
    }

    public synchronized boolean saveProof(Proof proof, OutputStream out) throws TransformerException {
        return saveProof(proof, out, true);
    }

    public synchronized boolean saveProof(Proof proof, OutputStream out, boolean saveAuthors) throws TransformerException {
        if (proof == null || out == null)
            return false;
        Document doc = documentBuilder.newDocument();
        Element root = doc.createElement("bram");
        doc.appendChild(root);

        Element program = doc.createElement("program");
        program.appendChild(doc.createTextNode(LibAris.NAME));
        root.appendChild(program);

        Element version = doc.createElement("version");
        version.appendChild(doc.createTextNode(LibAris.VERSION));
        root.appendChild(version);

        ArrayList<Element> proofElements = new ArrayList<>();

        createProofElement(proof, 0, doc, root, proofElements);

        Element baseProof = proofElements.get(0);

        for (int i = 0; i < proof.getNumGoals(); ++i) {
            Goal g = proof.getGoal(i);
            Element goal = doc.createElement("goal");
            Element sen = doc.createElement("sen");
            g.buildExpression();
            sen.appendChild(doc.createTextNode(g.getExpression() == null ? "" : g.getExpression().toString()));
            goal.appendChild(sen);
            Element raw = doc.createElement("raw");
            raw.appendChild(doc.createTextNode(g.getGoalString()));
            goal.appendChild(raw);
            baseProof.appendChild(goal);
        }

        Element metadata = doc.createElement("metadata");

        if (saveAuthors)
            for (String author : proof.getAuthors()) {
                Element a = doc.createElement("author");
                a.appendChild(doc.createTextNode(author));
                metadata.appendChild(a);
            }

        for (RuleList rule : proof.getAllowedRules()) {
            if (rule == null)
                continue;
            Element r = doc.createElement("allowed-rule");
            r.appendChild(doc.createTextNode(rule.name()));
            metadata.appendChild(r);
        }

        root.insertBefore(metadata, baseProof);

        DOMSource src = new DOMSource(doc);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        transformer.transform(src, result);

        String hash = computeHash(writer.toString(), proof.getAuthors());
        Element hashElement = doc.createElement("hash");
        hashElement.appendChild(doc.createTextNode(hash));
        metadata.appendChild(hashElement);

        result = new StreamResult(out);
        transformer.transform(src, result);
        proof.saved();
        return true;
    }

    private synchronized Pair<Integer, Integer> createProofElement(Proof proof, int lineNum, Document doc, Element root, ArrayList<Element> proofElements) {
        Element prf = doc.createElement("proof");
        int id = proofElements.size();
        prf.setAttribute("id", String.valueOf(id));
        root.appendChild(prf);
        proofElements.add(prf);
        int indent = proof.getLine(lineNum).getSubProofLevel();
        int allowedAssumptions = lineNum == 0 ? proof.getNumPremises() : 1;
        for (int i = lineNum; i < proof.getNumLines(); ++i) {
            Line line = proof.getLine(i);
            if (line.getSubProofLevel() < indent || (line.getSubProofLevel() == indent && allowedAssumptions == 0 && line.isAssumption()))
                break;
            if (line.isAssumption()) {
                if (allowedAssumptions == 0) {
                    Element step = doc.createElement("step");
                    step.setAttribute("linenum", String.valueOf(line.getLineNum()));
                    Element rule = doc.createElement("rule");
                    rule.appendChild(doc.createTextNode("SUBPROOF"));
                    step.appendChild(rule);
                    Pair<Integer, Integer> subproofResult = createProofElement(proof, line.getLineNum(), doc, root, proofElements);
                    Element premise = doc.createElement("premise");
                    premise.appendChild(doc.createTextNode(String.valueOf(subproofResult.getKey())));
                    step.appendChild(premise);
                    prf.appendChild(step);
                    lineNum = subproofResult.getValue();
                    i = lineNum - 1;
                } else {
                    --allowedAssumptions;
                    Element assumption = doc.createElement("assumption");
                    assumption.setAttribute("linenum", String.valueOf(line.getLineNum()));
                    Element sen = doc.createElement("sen");
                    line.buildExpression();
                    sen.appendChild(doc.createTextNode(line.getExpression() == null ? "" : line.getExpression().toString()));
                    assumption.appendChild(sen);
                    Element raw = doc.createElement("raw");
                    raw.appendChild(doc.createTextNode(line.getExpressionString()));
                    assumption.appendChild(raw);
                    TreeSet<String> constants = line.getConstants();
                    if (constants.size() > 0) {
                        Element c = doc.createElement("constants");
                        c.appendChild(doc.createTextNode(StringUtils.join(constants, ",")));
                        assumption.appendChild(c);
                    }
                    prf.appendChild(assumption);
                    ++lineNum;
                }
            } else {
                allowedAssumptions = 0;
                Element step = doc.createElement("step");
                step.setAttribute("linenum", String.valueOf(line.getLineNum()));
                Element sen = doc.createElement("sen");
                line.buildExpression();
                sen.appendChild(doc.createTextNode(line.getExpression() == null ? "" : line.getExpression().toString()));
                step.appendChild(sen);
                Element raw = doc.createElement("raw");
                raw.appendChild(doc.createTextNode(line.getExpressionString()));
                step.appendChild(raw);
                Element rule = doc.createElement("rule");
                rule.appendChild(doc.createTextNode(line.getSelectedRule() == null ? "" : line.getSelectedRule().name()));
                step.appendChild(rule);
                for (Line p : line.getPremises()) {
                    Element premise = doc.createElement("premise");
                    premise.appendChild(doc.createTextNode(String.valueOf(p.getLineNum())));
                    step.appendChild(premise);
                }
                prf.appendChild(step);
                ++lineNum;
            }
        }
        return new ImmutablePair<>(id, lineNum);
    }

    public synchronized Proof loadProof(File file, String author) throws IOException, TransformerException {
        if (file == null || !file.exists())
            return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            return loadProof(fis, file.getName(), author, true);
        }
    }

    private synchronized Proof loadProof(InputStream in, String name, String author, boolean verifyIntegrity) throws TransformerException, IOException {
        // using the SAXSource so we can specify an xml reader that prevents xxe exploits
        SAXSource src = new SAXSource(xmlReader, new InputSource(in));
        DOMResult result = new DOMResult();
        transformer.transform(src, result);

        if (!(result.getNode() instanceof Document))
            return null;
        Document doc = (Document) result.getNode();

        NodeList list = doc.getElementsByTagName("bram");
        if (list.getLength() != 1 || !(list.item(0) instanceof Element))
            throw new IOException("Invalid file format");
        Element root = (Element) list.item(0);

        Element program = getElementByTag(root, "program");
        Element version = getElementByTag(root, "version");
        boolean isArisFile = program.getTextContent().equals(LibAris.NAME);
        Proof proof;
        HashSet<String> authors = new HashSet<>();
        if (!isArisFile) {
            if (!listener.notArisFile(name, program.getTextContent(), version.getTextContent()))
                return null;
            authors.add("UNKNOWN");
            proof = new Proof(authors, author);
        } else {
            Element metadata = getElementByTag(root, "metadata");
            try {
                Element hashElement = getElementByTag(metadata, "hash");
                authors.addAll(getElementsByTag(metadata, "author").stream().map(Node::getTextContent).collect(Collectors.toList()));
                metadata.removeChild(hashElement);
                DOMSource s = new DOMSource(doc);
                StringWriter w = new StringWriter();
                StreamResult r = new StreamResult(w);
                transformer.transform(s, r);
                String xml = w.toString().replaceAll("\n[\t\\s\f\r\\x0B]*\n", "\n");
                if (verifyIntegrity && !verifyHash(xml, hashElement.getTextContent(), authors)) {
                    listener.integrityCheckFailed(name);
                    authors.clear();
                    authors.add("UNKNOWN");
                }
            } catch (IOException ignored) {
                authors.add("UNKNOWN");
            }
            if (!verifyIntegrity)
                authors.clear();
            Set<RuleList> allowedRules = getElementsByTag(metadata, "allowed-rule").stream().map(element -> {
                try {
                    return RuleList.valueOf(element.getTextContent());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }).collect(Collectors.toSet());
            proof = new Proof(authors, author, allowedRules);
        }
        ArrayList<Element> proofElements = getElementsByTag(root, "proof");
        if (proofElements.size() == 0)
            throw new IOException("Missing main proof element");
        proofElements.sort((e1, e2) -> {
            try {
                int i1 = Integer.valueOf(e1.getAttribute("id"));
                int i2 = Integer.valueOf(e2.getAttribute("id"));
                return Integer.compare(i1, i2);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        for (int i = 0; i < proofElements.size(); ++i) {
            Element e = proofElements.get(i);
            int id;
            try {
                id = Integer.valueOf(e.getAttribute("id"));
            } catch (NumberFormatException e1) {
                throw new IOException("Invalid id tag in proof element");
            }
            if (id != i)
                throw new IOException("Non sequential id tag found in proof element");
        }
        readProofElement(proof, proofElements, 0, 0, 0);
        Element baseProof = proofElements.get(0);
        ArrayList<Element> goals = getElementsByTag(baseProof, "goal");
        for (Element g : goals) {
            String raw;
            try {
                raw = getElementByTag(g, "raw").getTextContent();
            } catch (IOException e) {
                String sen = getElementByTag(g, "sen").getTextContent();
//                    try {
                raw = Expression.parseViaRust(sen).toDebugString();//new Expression(sen).toLogicString();
//                    } catch (ExpressionParseException e1) {
//                        throw new IOException("Invalid sentence in goal element");
//                    }
            }
            Goal goal = proof.addGoal(proof.getNumGoals());
            goal.setGoalString(raw, true);
        }
        proof.saved();
        return proof;
    }

    private synchronized int readProofElement(Proof proof, ArrayList<Element> proofElements, int elementId, int indent, int lineNum) throws IOException {
        Element element = proofElements.get(elementId);
        ArrayList<Element> assumptions = getElementsByTag(element, "assumption");
        assumptions.sort((e1, e2) -> {
            try {
                int i1 = Integer.valueOf(e1.getAttribute("linenum"));
                int i2 = Integer.valueOf(e2.getAttribute("linenum"));
                return Integer.compare(i1, i2);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        for (Element assumption : assumptions) {
            int num;
            try {
                num = Integer.parseInt(assumption.getAttribute("linenum"));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid assumptions linenum tag");
            }
            if (num != lineNum)
                throw new IOException("Non sequential linenum tags in file");
            String raw;
            try {
                raw = getElementByTag(assumption, "raw").getTextContent();
            } catch (IOException e) {
                String sen = getElementByTag(assumption, "sen").getTextContent();
//                try {
                raw = Expression.parseViaRust(sen).toDebugString();//new Expression(sen).toLogicString();
//                } catch (ExpressionParseException e1) {
//                    throw new IOException("Invalid sentence in proof element " + elementId);
//                }
            }
            Line line = indent == 0 ? proof.addPremise() : proof.addLine(lineNum, true, indent);
            line.setExpressionString(raw, true);
            if (indent > 0) {
                try {
                    String constantStr = getElementByTag(assumption, "constants").getTextContent();
                    String[] split = constantStr.split(",");
                    for (String s : split)
                        line.getConstants().add(s);
                } catch (IOException ignored) {
                }
            }
            ++lineNum;
        }
        ArrayList<Element> steps = getElementsByTag(element, "step");
        steps.sort((e1, e2) -> {
            try {
                int i1 = Integer.valueOf(e1.getAttribute("linenum"));
                int i2 = Integer.valueOf(e2.getAttribute("linenum"));
                return Integer.compare(i1, i2);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        for (Element step : steps) {
            int num;
            try {
                num = Integer.parseInt(step.getAttribute("linenum"));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid step linenum tag");
            }
            if (num != lineNum)
                throw new IOException("Non sequential linenum tags in file");
            String ruleStr = getElementByTag(step, "rule").getTextContent().toUpperCase();
            if (ruleStr.equals("SUBPROOF")) {
                int id;
                try {
                    id = Integer.valueOf(getElementByTag(step, "premise").getTextContent());
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid proof id in subproof step");
                }
                if (id <= elementId || id >= proofElements.size())
                    throw new IOException("Invalid proof id in subproof step");
                lineNum = readProofElement(proof, proofElements, id, indent + 1, lineNum);
            } else {
                String raw;
                try {
                    raw = getElementByTag(step, "raw").getTextContent();
                } catch (IOException e) {
                    String sen = getElementByTag(step, "sen").getTextContent();
//                    try {
                    raw = Expression.parseViaRust(sen).toDebugString();//new Expression(sen).toLogicString();
//                    } catch (ExpressionParseException e1) {
//                        throw new IOException("Invalid sentence in proof element " + elementId);
//                    }
                }
                Line line = proof.addLine(lineNum, false, indent);
                line.setExpressionString(raw, true);
                RuleList rule = null;
                try {
                    rule = RuleList.valueOf(ruleStr);
                } catch (IllegalArgumentException ignored) {
                }
                line.setSelectedRule(rule);
                ArrayList<Element> premises = getElementsByTag(step, "premise");
                for (Element p : premises) {
                    int pid;
                    try {
                        pid = Integer.valueOf(p.getTextContent());
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid premise id in step");
                    }
                    if (pid < 0 || pid >= lineNum)
                        throw new IOException("Invalid premise id in step");
                    proof.setPremise(lineNum, proof.getLine(pid), true);
                }
                ++lineNum;
            }
        }
        return lineNum;
    }

    private synchronized String computeHash(String xml, Collection<String> authorCollection) {
        ArrayList<String> authors = new ArrayList<>(authorCollection);
        Collections.sort(authors);
        String hashStr = Base64.getEncoder().encodeToString(hash.digest((xml + StringUtils.join(authors, "")).getBytes()));
        hash.reset();
        return hashStr;
    }

    private boolean verifyHash(String xml, String hash, Collection<String> authors) {
        return computeHash(xml, authors).equals(hash);
    }

    @Override
    public void convertProblem(@NotNull Problem<LibAris> problem, @NotNull OutputStream out, boolean isProblemSolution) throws IOException {
        try {
            saveProof(((ArisProofProblem) problem).getProof(), out, isProblemSolution);
        } catch (TransformerException e) {
            throw new IOException("Failed to save aris proof", e);
        }
    }

    @NotNull
    @Override
    public ArisProofProblem loadProblem(@NotNull InputStream in, boolean isProblemSolution) throws IOException {
        try {
            return new ArisProofProblem(loadProof(in, "Aris Assign", LibAris.getInstance().getProperties().get("username"), isProblemSolution));
        } catch (TransformerException e) {
            throw new IOException("Failed to load aris proof", e);
        }
    }
}
