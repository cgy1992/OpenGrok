/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

 /*
 * Copyright (c) 2007, 2017, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.web;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.history.RepositoryFactory;
import org.opensolaris.opengrok.util.FileUtilities;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.Assert.*;

/**
 * JUnit test to test that the DirectoryListing produce the expected result
 */
public class DirectoryListingTest {

    /**
     * Indication of that the file was a directory and so that the size given by
     * the FS is platform dependent.
     */
    private static final int DIRECTORY_INTERNAL_SIZE = -2;
    /**
     * Indication of unparseable file size.
     */
    private static final int INVALID_SIZE = -1;

    private File directory;
    private FileEntry[] entries;
    private SimpleDateFormat dateFormatter;

    class FileEntry implements Comparable {

        String name;
        String href;
        long lastModified;
        /**
         * May be:
         * <pre>
         * positive integer - for a file
         * -2 - for a directory
         * -1 - for an unparseable size
         * </pre>
         */
        int size;
        List<FileEntry> subdirs;

        FileEntry() {
            dateFormatter = new SimpleDateFormat("dd-MMM-yyyy");
        }

        private FileEntry(String name, String href, long lastModified, int size, List<FileEntry> subdirs) {
            this();
            this.name = name;
            this.href = href;
            this.lastModified = lastModified;
            this.size = size;
            this.subdirs = subdirs;
        }

        /**
         * Creating the directory entry.
         *
         * @param name name of the file
         * @param href href to the file
         * @param lastModified date of last modification
         * @param subdirs list of sub entries (may be empty)
         */
        FileEntry(String name, String href, long lastModified, List<FileEntry> subdirs) {
            this(name, href, lastModified, DIRECTORY_INTERNAL_SIZE, subdirs);
            assertNotNull(subdirs);
        }

        /**
         * Creating a regular file entry.
         *
         * @param name name of the file
         * @param href href to the file
         * @param lastModified date of last modification
         * @param size the desired size of the file on the disc
         */
        FileEntry(String name, String href, long lastModified, int size) {
            this(name, href, lastModified, size, null);
        }

        private void create() throws Exception {
            File file = new File(directory, name);

            if (subdirs != null && subdirs.size() > 0) {
                // this is a directory
                assertTrue("Failed to create a directory", file.mkdirs());
                for (FileEntry entry : subdirs) {
                    entry.name = name + File.separator + entry.name;
                    entry.create();
                }
            } else {
                assertTrue("Failed to create file", file.createNewFile());
            }

            long val = lastModified;
            if (val == Long.MAX_VALUE) {
                val = System.currentTimeMillis();
            }

            assertTrue("Failed to set modification time",
                    file.setLastModified(val));

            if (subdirs == null && size > 0) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[size];
                    out.write(buffer);
                }
            }
        }

        @Override
        public int compareTo(Object o) {
            int ret = -1;

            if (o instanceof FileEntry) {
                FileEntry fe = (FileEntry) o;

                // @todo verify all attributes!
                if (name.compareTo(fe.name) == 0
                        && href.compareTo(fe.href) == 0) {
                    if ( // this is a file so the size must be exact
                            (subdirs == null && size == fe.size)
                            // this is a directory so the size must have been "-" char
                            || (subdirs != null && size == DIRECTORY_INTERNAL_SIZE)) {
                        ret = 0;
                    }
                }
            }
            return ret;
        }

    }

    public DirectoryListingTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        directory = FileUtilities.createTemporaryDirectory("directory");

        entries = new FileEntry[3];
        entries[0] = new FileEntry("foo.c", "foo.c", 0, 112);
        entries[1] = new FileEntry("bar.h", "bar.h", Long.MAX_VALUE, 0);
        // Will test getSimplifiedPath() behavior for ignored directories.
        // Use DIRECTORY_INTERNAL_SIZE value for length so it is checked as the directory
        // should contain "-" (DIRECTORY_SIZE_PLACEHOLDER) string.
        entries[2] = new FileEntry("subdir", "subdir/", 0, Arrays.asList(
                new FileEntry[]{
                    new FileEntry("SCCS", "SCCS/", 0, Arrays.asList(
                            new FileEntry[]{
                                new FileEntry("version", "version", 0, 312)
                            })
                    )}
        ));

        for (FileEntry entry : entries) {
            entry.create();
        }

        // Create the entry that will be ignored separately.
        FileEntry hgtags = new FileEntry(".hgtags", ".hgtags", 0, 1);
        hgtags.create();

        // Need to populate list of ignored entries for all repository types.
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        RepositoryFactory.setIgnored(env);
    }

    @After
    public void tearDown() throws Exception {
        if (directory != null && directory.exists()) {
            removeDirectory(directory);
            directory.delete();
        }
    }

    private void removeDirectory(File dir) {
        File[] childs = dir.listFiles();
        if (childs != null) {
            for (File f : childs) {
                if (f.isDirectory()) {
                    removeDirectory(f);
                }
                f.delete();
            }
        }
    }

    /**
     * Get the href attribute from: &lt;td align="left"&gt;&lt;tt&gt;&lt;a
     * href="foo" class="p"&gt;foo&lt;/a&gt;&lt;/tt&gt;&lt;/td&gt;
     *
     * @param item
     * @return
     * @throws java.lang.Exception
     */
    private String getHref(Node item) throws Exception {
        Node a = item.getFirstChild(); // a
        assertNotNull(a);
        assertEquals(Node.ELEMENT_NODE, a.getNodeType());

        Node href = a.getAttributes().getNamedItem("href");
        assertNotNull(href);
        assertEquals(Node.ATTRIBUTE_NODE, href.getNodeType());

        return href.getNodeValue();
    }

    /**
     * Get the filename from: &lt;td align="left"&gt;&lt;tt&gt;&lt;a href="foo"
     * class="p"&gt;foo&lt;/a&gt;&lt;/tt&gt;&lt;/td&gt;
     *
     * @param item
     * @return
     * @throws java.lang.Exception
     */
    private String getFilename(Node item) throws Exception {
        Node a = item.getFirstChild(); // a
        assertNotNull(a);
        assertEquals(Node.ELEMENT_NODE, a.getNodeType());

        Node node = a.getFirstChild();
        assertNotNull(node);
        // If this is element node then it is probably a directory in which case
        // it contains the &lt;b&gt; element.
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            node = node.getFirstChild();
            assertNotNull(node);
            assertEquals(Node.TEXT_NODE, node.getNodeType());
        } else {
            assertEquals(Node.TEXT_NODE, node.getNodeType());
        }

        return node.getNodeValue();
    }

    /**
     * Get the LastModified date from the &lt;td&gt;date&lt;/td&gt;
     *
     * @todo fix the item
     * @param item the node representing &lt;td&gt
     * @return last modified date of the file
     * @throws java.lang.Exception if an error occurs
     */
    private long getLastModified(Node item) throws Exception {
        Node val = item.getFirstChild();
        assertNotNull(val);
        assertEquals(Node.TEXT_NODE, val.getNodeType());

        String value = val.getNodeValue();
        return value.equalsIgnoreCase("Today")
                ? Long.MAX_VALUE
                : dateFormatter.parse(value).getTime();
    }

    /**
     * Get the size from the: &lt;td&gt;&lt;tt&gt;size&lt;/tt&gt;&lt;/td&gt;
     *
     * @param item the node representing &lt;td&gt;
     * @return positive integer if the record was a file<br>
     * -1 if the size could not be parsed<br>
     * -2 if the record was a directory<br>
     */
    private int getSize(Node item) throws NumberFormatException {
        Node val = item.getFirstChild();
        assertNotNull(val);
        assertEquals(Node.TEXT_NODE, val.getNodeType());
        if (DirectoryListing.DIRECTORY_SIZE_PLACEHOLDER.equals(val.getNodeValue().trim())) {
            // track that it had the DIRECTORY_SIZE_PLACEHOLDER character
            return DIRECTORY_INTERNAL_SIZE;
        }
        try {
            return Integer.parseInt(val.getNodeValue().trim());
        } catch (NumberFormatException ex) {
            return INVALID_SIZE;
        }
    }

    /**
     * Validate this file-entry in the table
     *
     * @param element The &lt;tr&gt; element
     * @throws java.lang.Exception
     */
    private void validateEntry(Element element) throws Exception {
        FileEntry entry = new FileEntry();
        NodeList nl = element.getElementsByTagName("td");
        int len = nl.getLength();
        // There should be 5 columns or less in the table.
        if (len < 5) {
            return;
        }
        assertEquals(5, len);

        // item(0) is a decoration placeholder, i.e. no content
        entry.name = getFilename(nl.item(1));
        entry.href = getHref(nl.item(1));
        entry.lastModified = getLastModified(nl.item(3));
        entry.size = getSize(nl.item(4));

        // Try to look it up in the list of files.
        for (int ii = 0; ii < entries.length; ++ii) {
            if (entries[ii] != null && entries[ii].compareTo(entry) == 0) {
                entries[ii] = null;
                return;
            }
        }

        fail("Could not find a match for: " + entry.name);
    }

    /**
     * Test directory listing
     *
     * @throws java.lang.Exception if an error occurs while generating the list.
     */
    @Test
    public void directoryListing() throws Exception {
        StringWriter out = new StringWriter();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<start>\n");

        DirectoryListing instance = new DirectoryListing();
        instance.listTo("ctx", directory, out, directory.getPath(),
                Arrays.asList(directory.list()));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        assertNotNull("DocumentBuilderFactory is null", factory);

        DocumentBuilder builder = factory.newDocumentBuilder();
        assertNotNull("DocumentBuilder is null", builder);

        out.append("</start>\n");
        String str = out.toString();
        Document document = builder.parse(new ByteArrayInputStream(str.getBytes()));

        NodeList nl = document.getElementsByTagName("tr");
        int len = nl.getLength();
        // Add one extra for header and one for parent directory link.
        assertEquals(entries.length + 2, len);
        // Skip the the header and parent link.
        for (int i = 2; i < len; ++i) {
            validateEntry((Element) nl.item(i));
        }
    }
}
