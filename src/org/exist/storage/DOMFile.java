package org.exist.storage;

/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001,  Wolfgang M. Meier (meier@ifs.tu-darmstadt.de)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU Library General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 *  $Id$
 */
import it.unimi.dsi.fastutil.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.Object2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import org.apache.log4j.Logger;
import org.dbxml.core.DBException;
import org.dbxml.core.data.Value;
import org.dbxml.core.filer.BTree;
import org.dbxml.core.filer.BTreeCallback;
import org.dbxml.core.filer.BTreeException;
import org.dbxml.core.indexer.IndexQuery;
import org.exist.dom.DocumentImpl;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.util.ByteConversion;
import org.exist.util.Lock;
import org.exist.util.LockException;
import org.exist.util.Lockable;
import org.exist.util.ReadOnlyException;
import org.exist.util.SimpleTimeOutLock;
import org.exist.util.XMLUtil;

/**
 *  DOMFile represents the central storage file for DOM nodes.
 * 
 * Nodes are stored in sequential order to allow fast access when
 * serializing a document or fragment. Pages have previous-page/next-page
 * links. Each node has a virtual address,
 * which consists of a page-number/tid pair. The tid is a virtual offset
 * into the page. A node may be moved to a new page on node insertions.
 * However, the tid will always remain the same.
 *
 *@author     Wolfgang Meier <meier@ifs.tu-darmstadt.de>
 *@created    25. Mai 2002
 */
public class DOMFile extends BTree implements Lockable {

	// page types
	public final static byte FREE_LIST = 22;
	public final static byte LOB = 21;
	public final static byte RECORD = 20;

	protected static int GROW_BY = 8;

	private static Logger LOG = Logger.getLogger(DOMFile.class);

	private final ClockPageBuffer buffer;
	private DOMFileHeader fileHeader;
	private Object owner = null;

	private Lock lock = new SimpleTimeOutLock();

	private final Object2LongOpenHashMap pages = new Object2LongOpenHashMap();
		
	/**
	 *  Constructor for the DOMFile object
	 *
	 *@param  buffers      the number of btree buffers to use
	 *@param  dataBuffers  the number of data page buffers
	 */
	public DOMFile(int buffers, int dataBuffers) {
		super(buffers);
		fileHeader = (DOMFileHeader) getFileHeader();
		fileHeader.setPageCount(0);
		fileHeader.setTotalCount(0);
		buffer = new ClockPageBuffer(dataBuffers);
	}

	/**
	 *  Constructor for the DOMFile object
	 *
	 *@param  file  the file to use
	 */
	public DOMFile(File file) {
		this(256, 256);
		setFile(file);
	}

	/**
	 *  Constructor for the DOMFile object
	 *
	 *@param  file     the file to use
	 *@param  buffers  size of the data page buffer
	 */
	public DOMFile(File file, int buffers) {
		this(buffers, 256);
		setFile(file);
	}

	/**
	 *  Constructor for the DOMFile object
	 *
	 *@param  file         the file to use 
	 *@param  buffers      size of the buffer for btree pages
	 *@param  dataBuffers  size of the buffer for data pages
	 */
	public DOMFile(File file, int buffers, int dataBuffers) {
		this(buffers, dataBuffers);
		setFile(file);
	}

	/**
	 *  Constructor for the DOMFile object.
	 * 
	 * Use this constructor if all keys have the same length.
	 *
	 *@param  file     the file to use
	 *@param  buffers  size of the data page buffer
	 *@param  keyLen   key size if all keys have the same length
	 */
	public DOMFile(File file, int buffers, short keyLen) {
		this(file, buffers);
		fileHeader.setKeyLen(keyLen);
	}

	/**
	 *  Create virtual address from page number and offset (tid)
	 *
	 *@param  page    page number
	 *@param  offset  offset (tid)
	 *@return         new virtual address in a long
	 */
	public final static long createPointer(int page, int offset) {
		long p = (page & 0xffff);
		long o = (offset & 0xffff);
		return page | (o << 32);
	}

	/**
	 *  Get the tid from a virtual address
	 *
	 *@param  pointer  
	 *@return          the tid encoded in this address
	 */
	public final static int tidFromPointer(long pointer) {
		return (int) ((pointer >>> 32) & 0xffff);
	}

	/**
	 *  Get the page from a virtual address
	 *
	 *@param  pointer  
	 *@return          the page encoded in this address
	 */
	public final static int pageFromPointer(long pointer) {
		return (int) pointer;
	}

	/**
	 *  Append a value to the current page 
	 *
	 *@param  value  the value to append
	 *@return        the virtual storage address of the value
	 */
	public long add(byte[] value) throws ReadOnlyException {
		if (value == null)
			return -1;
		final int valueLen = value.length;
		// always append data to the end of the file
		DOMPage page = getCurrentPage();
		// does value fit into current data page?
		if (page == null || page.len + 4 + valueLen > page.data.length) {
			DOMPage newPage = new DOMPage();
			if (page != null) {
				DOMFilePageHeader ph = page.getPageHeader();
				ph.setNextDataPage(newPage.getPageNum());
				newPage.getPageHeader().setPrevDataPage(page.getPageNum());
				page.setDirty(true);
				buffer.add(page);
			}
			page = newPage;
			setCurrentPage(newPage);
		}
		// save tuple identifier
		final DOMFilePageHeader ph = page.getPageHeader();
		final short tid = ph.getNextTID();
		ByteConversion.shortToByte(tid, page.data, page.len);
		page.len += 2;
		// save data length
		ByteConversion.shortToByte((short) valueLen, page.data, page.len);
		page.len += 2;
		// save data
		System.arraycopy(value, 0, page.data, page.len, valueLen);
		page.len += valueLen;
		ph.incRecordCount();
		ph.setDataLength(page.len);
		page.setDirty(true);
		buffer.add(page, 2);
		// create pointer from pageNum and offset into page
		final long p = createPointer((int) page.getPageNum(), tid);
		return p;
	}

	public long insertAfter(Value key, byte[] value) {
		try {
			final long p = findValue(key);
			if(p == KEY_NOT_FOUND)
				return -1;
			return insertAfter(p, value);
		} catch (BTreeException e) {
			LOG.warn("key not found", e);
		} catch (IOException e) {
			LOG.warn("IO error", e);
		}
		return -1;
	}

	public long insertAfter(long address, byte[] value) {
		RecordPos rec = findValuePosition(address);
		if (rec == null) {
			LOG.warn("page not found");
			return -1;
		}
		short l = ByteConversion.byteToShort(rec.page.data, rec.offset);
		rec.offset = rec.offset + l + 2;
		int dataLen = rec.page.getPageHeader().getDataLength();
		// insert in the middle of the page?
		if (rec.offset < dataLen) {
			if (dataLen + value.length + 4 < fileHeader.getWorkSize()) {
				int end = rec.offset + value.length + 4;
				System.arraycopy(
					rec.page.data,
					rec.offset,
					rec.page.data,
					end,
					dataLen - rec.offset);
				rec.page.len = dataLen + value.length + 4;
				rec.page.getPageHeader().setDataLength(rec.page.len);
			} else {
				// split the page
				DOMPage splitPage = new DOMPage();
				splitPage.len = dataLen - rec.offset;
				System.arraycopy(
					rec.page.data,
					rec.offset,
					splitPage.data,
					0,
					splitPage.len);
				splitPage.getPageHeader().setDataLength(splitPage.len);
				splitPage.getPageHeader().setNextDataPage(
					rec.page.getPageHeader().getNextDataPage());
				splitPage.getPageHeader().setPrevDataPage(
					rec.page.getPageNum());
				splitPage.getPageHeader().setNextTID(
					rec.page.getPageHeader().getNextTID());
				splitPage.getPageHeader().setRecordCount(
					getRecordCount(splitPage));
				splitPage.setDirty(true);
				buffer.add(splitPage);
				DOMPage nextPage =
					getCurrentPage(splitPage.getPageHeader().getNextDataPage());
				nextPage.getPageHeader().setPrevDataPage(
					splitPage.getPageNum());
				nextPage.setDirty(true);
				buffer.add(nextPage);
				rec.page.getPageHeader().setNextDataPage(
					splitPage.getPageNum());
				rec.page.len = rec.offset + value.length + 4;
				rec.page.getPageHeader().setDataLength(rec.page.len);
				rec.page.getPageHeader().setRecordCount(
					getRecordCount(rec.page));
				dataLen = rec.offset;
			}
		} else if (dataLen + value.length + 4 > fileHeader.getWorkSize()) {
			// append at the end of the page
			// does value fit into page?
			DOMPage newPage = new DOMPage();
			newPage.getPageHeader().setNextDataPage(
				rec.page.getPageHeader().getNextDataPage());
			rec.page.getPageHeader().setNextDataPage(newPage.getPageNum());
			rec.page.setDirty(true);
			buffer.add(rec.page);
			rec.page = newPage;
			rec.offset = 0;
			rec.page.len = value.length + 4;
			rec.page.getPageHeader().setDataLength(rec.page.len);
		} else {
			rec.page.len = dataLen + value.length + 4;
			rec.page.getPageHeader().setDataLength(rec.page.len);
		}

		// write the data
		short tid = rec.page.getPageHeader().getNextTID();
		ByteConversion.shortToByte((short) tid, rec.page.data, rec.offset);
		rec.offset += 2;
		ByteConversion.shortToByte(
			(short) value.length,
			rec.page.data,
			rec.offset);
		rec.offset += 2;
		System.arraycopy(value, 0, rec.page.data, rec.offset, value.length);
		rec.offset += value.length;
		rec.page.getPageHeader().incRecordCount();
		rec.page.setDirty(true);
		buffer.add(rec.page);
		return createPointer((int) rec.page.getPageNum(), tid);
	}

	/**
	 *  Description of the Method
	 *
	 *@return                  Description of the Return Value
	 *@exception  DBException  Description of the Exception
	 */
	public boolean close() throws DBException {
		flush();
		super.close();
		return true;
	}

	/**
	 *  Description of the Method
	 *
	 *@return                  Description of the Return Value
	 *@exception  DBException  Description of the Exception
	 */
	public boolean create() throws DBException {
		if (super.create((short) 12))
			return true;
		else
			return false;
	}

	/**
	 *  Description of the Method
	 *
	 *@return    Description of the Return Value
	 */
	public FileHeader createFileHeader() {
		return new DOMFileHeader(1024, PAGE_SIZE);
	}

	/**
	 *  Description of the Method
	 *
	 *@param  read             Description of the Parameter
	 *@return                  Description of the Return Value
	 *@exception  IOException  Description of the Exception
	 */
	public FileHeader createFileHeader(boolean read) throws IOException {
		return new DOMFileHeader(read);
	}

	/**
	 *  Description of the Method
	 *
	 *@param  pageCount  Description of the Parameter
	 *@return            Description of the Return Value
	 */
	public FileHeader createFileHeader(long pageCount) {
		return new DOMFileHeader(pageCount, PAGE_SIZE);
	}

	/**
	 *  Description of the Method
	 *
	 *@param  pageCount  Description of the Parameter
	 *@param  pageSize   Description of the Parameter
	 *@return            Description of the Return Value
	 */
	public FileHeader createFileHeader(long pageCount, int pageSize) {
		return new DOMFileHeader(pageCount, pageSize);
	}

	/**
	 *  Description of the Method
	 *
	 *@return    Description of the Return Value
	 */
	protected Page createNewPage() {
		try {
			Page page = getFreePage();
			DOMFilePageHeader ph = (DOMFilePageHeader) page.getPageHeader();
			ph.setStatus(RECORD);
			ph.setDirty(true);
			ph.setNextDataPage(-1);
			ph.setPrevDataPage(-1);
			ph.setNextTID((short)-1);
			ph.setDataLength(0);
			ph.setRecordCount((short) 0);
			//page.write();
			return page;
		} catch (IOException ioe) {
			LOG.warn(ioe);
			return null;
		}
	}

	/**
	 *  Description of the Method
	 *
	 *@return    Description of the Return Value
	 */
	public PageHeader createPageHeader() {
		return new DOMFilePageHeader();
	}

	/**
	 *  Description of the Method
	 *
	 *@param  query               Description of the Parameter
	 *@return                     Description of the Return Value
	 *@exception  IOException     Description of the Exception
	 *@exception  BTreeException  Description of the Exception
	 */
	public ArrayList findKeys(IndexQuery query)
		throws IOException, BTreeException {
		final FindCallback cb = new FindCallback(FindCallback.KEYS);
		query(query, cb);
		return cb.getValues();
	}

	private long findNode(NodeImpl node, long target, Iterator iter) {
		if (node.hasChildNodes()) {
			final long firstChildId =
				XMLUtil.getFirstChildId(
					(DocumentImpl) node.getOwnerDocument(),
					node.getGID());
			if (firstChildId < 0)
				return 0;
			final long lastChildId = firstChildId + node.getChildCount();
			//LOG.debug("scanning " + firstChildId + " to " + lastChildId);
			long p;
			for (long gid = firstChildId; gid < lastChildId; gid++) {
				Value value = (Value) iter.next();
				if (gid == target)
					return ((DOMFileIterator) iter).currentAddress();
				NodeImpl child =
					NodeImpl.deserialize(
						value.getData(),
						(DocumentImpl) node.getOwnerDocument());
				child.setGID(gid);
				if (node.hasChildNodes()
					&& (p = findNode(child, target, iter)) != 0)
					return p;
			}
		}
		return 0;
	}

	/**
	 *  Retrieve a range of nodes, starting at first and including last.
	 *
	 *@param  first               the first node to retrieve
	 *@param  last                the last node to retrieve
	 *@return                     list of nodes
	 *@exception  IOException     Description of the Exception
	 *@exception  BTreeException  Description of the Exception
	 */
	public ArrayList findRange(Value first, Value last)
		throws IOException, BTreeException {
		final IndexQuery query =
			new IndexQuery(null, IndexQuery.BW, first, last);
		final RangeCallback cb = new RangeCallback();
		query(query, cb);
		return cb.getValues();
	}

	private long findValue(Object lock, NodeProxy node)
		throws IOException, BTreeException {
		final DocumentImpl doc = (DocumentImpl) node.getDoc();
		final NativeBroker.NodeRef nodeRef =
			new NativeBroker.NodeRef(doc.getDocId(), node.getGID());
		// first try to find the node in the index
		final long p = findValue(nodeRef);
		if(p == KEY_NOT_FOUND) {
			//LOG.debug("node " + node.gid + " not found, trying parent.");
			// node not found in index: try to find the nearest available
			// ancestor and traverse it
			long id = node.getGID();
			long parentPointer = -1;
			while (parentPointer < 0) {
				if (id < 1)
					throw new BTreeException(
						"node " + node.gid + " not found.");
				id = XMLUtil.getParentId(doc, id);
				NativeBroker.NodeRef parentRef =
					new NativeBroker.NodeRef(doc.getDocId(), id);
				try {
					parentPointer = findValue(parentRef);
				} catch (BTreeException bte) {
				}
			}
			final long firstChildId = XMLUtil.getFirstChildId(doc, id);
			final Iterator iter =
				new DOMFileIterator(lock, doc, this, parentPointer);
			final Value value = (Value) iter.next();
			final NodeImpl n = NodeImpl.deserialize(value.getData(), doc);
			n.setGID(id);
			final long address = findNode(n, node.gid, iter);
			return address == 0 ? -1 : address;
		} else
			return p;
	}

	/**
	 *  Find matching nodes for the given query. 
	 *
	 *@param  query               Description of the Parameter
	 *@return                     Description of the Return Value
	 *@exception  IOException     Description of the Exception
	 *@exception  BTreeException  Description of the Exception
	 */
	public ArrayList findValues(IndexQuery query)
		throws IOException, BTreeException {
		FindCallback cb = new FindCallback(FindCallback.VALUES);
		query(query, cb);
		return cb.getValues();
	}

	/**
	 *  Flush all buffers to disk.
	 *
	 *@return                  Description of the Return Value
	 *@exception  DBException  Description of the Exception
	 */
	public boolean flush() throws DBException {
		super.flush();
		buffer.flush();
		pages.remove(owner);
		try {
			if (fileHeader.isDirty())
				fileHeader.write();
		} catch (IOException ioe) {
			LOG.debug("sync failed", ioe);
		}
		return true;
	}

	public void sync() throws DBException {
		super.flush();
		buffer.clear();
		pages.remove(owner);
		try {
			if (fileHeader.isDirty())
				fileHeader.write();
		} catch (IOException ioe) {
			LOG.warn("sync failed", ioe);
		}
	}

	public void printStatistics() {
		super.printStatistics();
		buffer.printStatistics();
	}
	
	public BufferStats getDataBufferStats() {
		return new BufferStats(buffer.blockBuffers,
			buffer.map.size(), buffer.hits, buffer.misses);
	}

	/**
	 *  Retrieve a node by key
	 *
	 *@param  key  
	 *@return      Description of the Return Value
	 */
	public Value get(Value key) {
		try {
			long p = findValue(key);
			if (p == KEY_NOT_FOUND)
				return null;
			return get(p);
		} catch (BTreeException bte) {
			return null;
			// key not found
		} catch (IOException ioe) {
			LOG.debug(ioe);
			return null;
		}
	}

	/**
	 *  Retrieve a node described by the given NodeProxy.
	 *
	 *@param  node  Description of the Parameter
	 *@return       Description of the Return Value
	 */
	public Value get(NodeProxy node) {
		try {
			long p = findValue(owner, node);
			if (p == KEY_NOT_FOUND)
				return null;
			return get(p);
		} catch (BTreeException bte) {
			return null;
		} catch (IOException ioe) {
			LOG.debug(ioe);
			return null;
		}
	}

	/**
	 *  Retrieve node at virtual address p.
	 *
	 *@param  p  Description of the Parameter
	 *@return    Description of the Return Value
	 */
	public Value get(long p) {
		RecordPos rec = findValuePosition(p);
		short l = ByteConversion.byteToShort(rec.page.data, rec.offset);
		Value v = new Value(rec.page.data, rec.offset + 2, l);
		v.setAddress(p);
		return v;
	}

	private final static class RecordPos {
		int offset = -1;
		DOMPage page = null;

		public RecordPos(int offset, DOMPage page) {
			this.offset = offset;
			this.page = page;
		}
	}

	private final RecordPos findValuePosition(long p) {
		long pageNr = (long) pageFromPointer(p);
		final short tid = (short) tidFromPointer(p);
		DOMPage page;
		int pos;
		while (pageNr > -1) {
			page = getCurrentPage(pageNr);
			buffer.add(page);
			pos = 0;
			final int dlen = page.getPageHeader().getDataLength();
			//System.out.println(pos + " < " + dlen);
			while (pos < dlen) {
				final short current =
					ByteConversion.byteToShort(page.data, pos);
				//System.out.println(current + " = " + tid);
				if (current == tid)
					return new RecordPos(pos + 2, page);
				pos = pos + ByteConversion.byteToShort(page.data, pos + 2) + 4;
			}
			pageNr = page.getPageHeader().getNextDataPage();
//			LOG.debug(owner.toString() + 
//				": tid "
//					+ tid
//					+ " not found on "
//					+ page.page.getPageInfo()
//					+ ". Loading "
//					+ pageNr);
		}
		LOG.debug("tid " + tid + " not found.");
		return null;
	}

	private final short getRecordCount(DOMPage page) {
		int pos = 0, len;
		short count = 0;
		while (pos < page.getPageHeader().getDataLength()) {
			len = ByteConversion.byteToShort(page.data, pos + 2);
			pos = pos + len + 4;
			count++;
		}
		return count;
	}

	/**
	 *  Retrieve the last page in the current sequence.
	 *
	 *@return    The currentPage value
	 */
	private final DOMPage getCurrentPage() {
		if (!pages.containsKey(owner)) {
			final DOMPage page = new DOMPage();
			pages.put(owner, page.page.getPageNum());
			buffer.add(page);
			return page;
		} else
			return getCurrentPage(pages.getLong(owner));
	}

	/**
	 *  Retrieve the page with page number p
	 *
	 *@param  p  Description of the Parameter
	 *@return    The currentPage value
	 */
	private final DOMPage getCurrentPage(long p) {
		DOMPage page = (DOMPage) buffer.get(p);
		if (page == null) {
			page = new DOMPage(p);
		}
		return page;
	}

	public void closeDocument() {
		pages.remove(owner);
	}
	
	/**
	 *@return    The rootNode value
	 */
	//    protected BTreeNode getRootNode() {
	//        try {
	//            if (currentDoc.getRootPage() < 0) {
	//                long rootPage = createRootNode();
	//                currentDoc.setRootPage(rootPage);
	//            }
	//            BTreeNode node = (BTreeNode) cache.get(currentDoc.getRootPage());
	//            if (node == null) {
	//            	LOG.debug("reading root: " + currentDoc.getRootPage());
	//                Page p = getPage(currentDoc.getRootPage());
	//                node = new BTreeNode(p);
	//                node.read();
	//            }
	//            cache.add(node);
	//            return node;
	//        } catch (Exception e) {
	//            System.err.println(e);
	//            e.printStackTrace();
	//            return null;
	//        }
	//    }

	/**
	 *  Get a node iterator starting at the address of the given
	 * NodeProxy.
	 *
	 *@param  doc   Description of the Parameter
	 *@param  node  Description of the Parameter
	 *@return       Description of the Return Value
	 */
	public Iterator iterator(DocumentImpl doc, NodeProxy node) {
		try {
			return new DOMFileIterator(owner, doc, this, node);
		} catch (IOException ioe) {
			LOG.warn(ioe);
		} catch (BTreeException bte) {
			LOG.warn(bte);
		}
		return null;
	}

	/**
	 *  Get a node iterator starting at the given address.
	 *
	 *@param  doc      Description of the Parameter
	 *@param  address  Description of the Parameter
	 *@return          Description of the Return Value
	 */
	public Iterator iterator(DocumentImpl doc, long address) {
		try {
			return new DOMFileIterator(owner, doc, this, address);
		} catch (IOException ioe) {
			LOG.warn(ioe);
		} catch (BTreeException bte) {
			LOG.warn(bte);
		}
		return null;
	}

	/**
	 *  Open the file.
	 *
	 *@return                  Description of the Return Value
	 *@exception  DBException  Description of the Exception
	 */
	public boolean open() throws DBException {
		if (super.open())
			return true;
		else
			return false;
	}

	/**
	 *  Put a new key/value pair.
	 *
	 *@param  key    Description of the Parameter
	 *@param  value  Description of the Parameter
	 *@return        Description of the Return Value
	 */
	public long put(Value key, byte[] value) throws ReadOnlyException {
		long p = add(value);
		try {
			addValue(key, p);
		} catch (IOException ioe) {
			LOG.debug(ioe);
			return -1;
		} catch (BTreeException bte) {
			LOG.debug(bte);
			return -1;
		}
		return p;
	}

	/**
	 *  Remove a key/value pair.
	 *
	 *@param  key  Description of the Parameter
	 */
	public void remove(Value key) {
		try {
			long p = findValue(key);
			if(p == KEY_NOT_FOUND)
				return;
			remove(key, p);
		} catch (BTreeException bte) {
			LOG.debug(bte);
		} catch (IOException ioe) {
			LOG.debug(ioe);
		}
	}

	public void remove(long p) {
		RecordPos rec = findValuePosition(p);
		DOMFilePageHeader ph = rec.page.getPageHeader();
		short l = ByteConversion.byteToShort(rec.page.data, rec.offset);
		int end = rec.offset + 2 + l;
		int len = ph.getDataLength();
		// remove old value
		System.arraycopy(
			rec.page.data,
			end,
			rec.page.data,
			rec.offset - 2,
			len - end);
		ph.setDirty(true);
		len = len - l - 4;
		ph.setDataLength(len);
		rec.page.len = len;
		rec.page.setDirty(true);
		ph.decRecordCount();
		if (ph.getRecordCount() == 0) {
			buffer.remove(rec.page);
			long np = ph.getNextDataPage();
			if(ph.getPrevDataPage() > -1) {
				DOMPage prev = getCurrentPage(ph.getPrevDataPage());
				prev.getPageHeader().setNextDataPage(np);
				prev.setDirty(true);
				buffer.add(prev);
			}
			try {
				ph.setNextDataPage(-1);
				ph.setDataLength(0);
				ph.setDataLength(0);
				//ph.setNextTID((short)0);
				ph.setRecordCount((short) 0);
				unlinkPages(rec.page.page);
			} catch (IOException ioe) {
				LOG.warn(ioe);
			}
			rec.page = null;
		} else
			buffer.add(rec.page);
	}

	/**
	 *  Remove the value at address p.
	 *
	 *@param  p  Description of the Parameter
	 */
	public void remove(Value key, long p) {
		remove(p);
		try {
			removeValue(key);
		} catch (BTreeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 *  Set the last page in the sequence to which nodes are
	 * currently appended.
	 *
	 *@param  page  The new currentPage value
	 */
	private final void setCurrentPage(DOMPage page) {
		final long pnum = pages.getLong(owner);
		if (pnum == page.page.getPageNum())
			return;
		//pages.remove(owner);
		pages.put(owner, page.page.getPageNum());
	}

	/**
	 * Get the active Lock object for this file.
	 * 
	 * @see org.exist.util.Lockable#getLock()
	 */
	public final Lock getLock() {
		return lock;
	}

	/**
	 *  Set the file location for this DOMFile.
	 *
	 *@param  location  The new location value
	 */
	public void setLocation(String location) {
		setFile(new File(location + ".dbx"));
	}

	/**
	 *  The current object owning this file.
	 * 
	 *
	 *@param  obj  The new ownerObject value
	 */
	public final void setOwnerObject(Object obj) {
		owner = obj;
	}

	public final void releaseOwner(Object obj) {
		//pages.remove(obj);
	}

	/**
	 *  Set the rootNode of the B+-tree
	 *
	 *@param  rootNode         The new rootNode value
	 *@exception  IOException  Description of the Exception
	 */
	//    protected void setRootNode(BTreeNode rootNode) throws IOException {
	//        if (currentDoc != null)
	//            currentDoc.setRootPage(rootNode.page.getPageNum());
	//        cache.add(rootNode);
	//    }

	/**
	 *  Update the key/value pair.
	 *
	 *@param  key    Description of the Parameter
	 *@param  value  Description of the Parameter
	 *@return        Description of the Return Value
	 */
	public boolean update(Value key, byte[] value) throws ReadOnlyException {
		try {
			long p = findValue(key);
			if (p == KEY_NOT_FOUND)
				return false;
			// key not found
			update(key, p, value);
		} catch (BTreeException bte) {
			LOG.debug(bte);
			bte.printStackTrace();
			return false;
		} catch (IOException ioe) {
			LOG.debug(ioe);
			return false;
		}
		return true;
	}

	/**
	 *  Update the key/value pair where the value is found at
	 * address p.
	 *
	 *@param  key    Description of the Parameter
	 *@param  p      Description of the Parameter
	 *@param  value  Description of the Parameter
	 */
	public void update(Value key, long p, byte[] value)
		throws ReadOnlyException {
		RecordPos rec = findValuePosition(p);
		short l = ByteConversion.byteToShort(rec.page.data, rec.offset);
		if (value.length < l) {
			// value is smaller than before
			System.out.println(value.length + " < " + l);
			System.out.println(rec.page.page.getPageInfo() + "; offset = " +
				rec.offset + "; data-len = " + 
				rec.page.getPageHeader().getDataLength() +
				"; previous-page = " + rec.page.getPageHeader().getPrevDataPage());
			throw new RuntimeException("shrinked");
//			int next = rec.offset + 2 + l;
//			ByteConversion.shortToByte(
//				(short) value.length,
//				rec.page.data,
//				rec.offset);
//			rec.offset += 2;
//			System.arraycopy(value, 0, rec.page.data, rec.offset, value.length);
//			rec.offset += value.length;
//			System.arraycopy(
//				rec.page.data,
//				next,
//				rec.page.data,
//				rec.offset,
//				rec.page.getPageHeader().getDataLength() - next);
//			rec.page.len = rec.page.len - (l - value.length);
		} else if (value.length > l) {
			throw new IllegalStateException("value too long");
		} else {
			// value length unchanged
			System.arraycopy(
				value,
				0,
				rec.page.data,
				rec.offset + 2,
				value.length);
		}
		rec.page.setDirty(true);
	}

	private final class DOMFileIterator implements Iterator {
		DOMFile db = null;
		DocumentImpl doc = null;
		Value nextVal = null;
		NodeProxy node = null;
		int offset;
		short lastTID = -1;
		DOMPage p = null;
		long page;
		long startAddress = -1;
		Object lockKey;

		/**
		 *  Constructor for the DOMFileIterator object
		 *
		 *@param  doc                 Description of the Parameter
		 *@param  db                  Description of the Parameter
		 *@param  node                Description of the Parameter
		 *@exception  BTreeException  Description of the Exception
		 *@exception  IOException     Description of the Exception
		 */
		public DOMFileIterator(
			Object lock,
			DocumentImpl doc,
			DOMFile db,
			NodeProxy node)
			throws BTreeException, IOException {
			this.db = db;
			this.node = node;
			this.doc = doc;
			lockKey = (lock == null ? this : lock);
		}

		/**
		 *  Constructor for the DOMFileIterator object
		 *
		 *@param  doc                 Description of the Parameter
		 *@param  db                  Description of the Parameter
		 *@param  address             Description of the Parameter
		 *@exception  BTreeException  Description of the Exception
		 *@exception  IOException     Description of the Exception
		 */
		public DOMFileIterator(
			Object lock,
			DocumentImpl doc,
			DOMFile db,
			long address)
			throws BTreeException, IOException {
			this.db = db;
			this.startAddress = address;
			this.doc = doc;
			lockKey = (lock == null ? this : lock);
		}

		/**
		 *  Gets the currentAddress attribute of the DOMFileIterator object
		 *
		 *@return    The currentAddress value
		 */
		public long currentAddress() {
			return createPointer((int) page, lastTID);
		}

		/**
		 *  Description of the Method
		 *
		 *@return    Description of the Return Value
		 */
		public boolean hasNext() {
			Lock lock = db.getLock();
			try {
				try {
					lock.acquire(lockKey);
					lock.enter(lockKey);
				} catch (LockException e) {
					return false;
				}
				if (node != null) {
					db.setOwnerObject(lockKey);
					long addr = db.findValue(lockKey, node);
					if(addr == KEY_NOT_FOUND)
						return false;
					RecordPos rec = findValuePosition(addr);
					page = rec.page.getPageNum();
					p = rec.page;
					offset = rec.offset - 2;
					node = null;
				} else if (-1 < startAddress) {
					RecordPos rec = findValuePosition(startAddress);
					page = rec.page.getPageNum();
					offset = rec.offset - 2;
					p = rec.page;
				} else if (page > -1)
					p = db.getCurrentPage(page);
				else {
					lock.release(lockKey);
					return false;
				}
				db.buffer.add(p);
				DOMFilePageHeader ph = p.getPageHeader();
				lock.release(lockKey);
				if (offset < ph.getDataLength())
					return true;
				else if (ph.getNextDataPage() < 0)
					return false;
				else
					return true;
			} catch (BTreeException e) {
				LOG.warn(e);
			} catch (IOException e) {
				LOG.warn(e);
			}
			lock.release(lockKey);
			return false;
		}

		/**
		 *  Description of the Method
		 *
		 *@return    Description of the Return Value
		 */
		public Object next() {
			Lock lock = db.getLock();
			try {
				try {
					lock.acquire(lockKey);
					lock.enter(lockKey);
				} catch (LockException e) {
					return null;
				}
				if (node != null) {
					db.setOwnerObject(lockKey);
					long addr = db.findValue(lockKey, node);
					if(addr == KEY_NOT_FOUND)
						return null;
					RecordPos rec = findValuePosition(addr);
					page = rec.page.getPageNum();
					p = rec.page;
					offset = rec.offset - 2;
					node = null;
				} else if (-1 < startAddress) {
					RecordPos rec = findValuePosition(startAddress);
					page = rec.page.getPageNum();
					offset = rec.offset - 2;
					p = rec.page;
					startAddress = -1;
				} else if (page > -1)
					p = db.getCurrentPage(page);
				else {
					lock.release(lockKey);
					return null;
				}
				DOMFilePageHeader ph = p.getPageHeader();
				if (offset >= ph.getDataLength()) {
					long nextPage = ph.getNextDataPage();
					if (nextPage < 0) {
						LOG.debug(
							"bad link to next " + p.page.getPageInfo());
						lock.release(lockKey);
						return null;
					}
					page = nextPage;
					p = db.getCurrentPage(nextPage);
					offset = 0;
				}
				db.buffer.add(p);
				short tid = ByteConversion.byteToShort(p.data, offset);
				short l = ByteConversion.byteToShort(p.data, offset + 2);
				int dataStart = offset + 4;
				Value nextVal = new Value(p.data, dataStart, l);
				nextVal.setAddress(createPointer((int) page, tid));
				lastTID = tid;
				offset = dataStart + l;
				lock.release(lockKey);
				return nextVal;
			} catch (BTreeException e) {
				LOG.warn(e);
			} catch (IOException e) {
				LOG.warn(e);
			}
			lock.release(lockKey);
			return null;
		}

		/**  Description of the Method */
		public void remove() {
			Lock lock = db.getLock();
			try {
				lock.acquire(lockKey, Lock.WRITE_LOCK);
				lock.enter(lockKey);
				DOMPage p = null;
				p = db.getCurrentPage(page);
				DOMFilePageHeader ph = p.getPageHeader();
				ph.decRecordCount();
				p.setDirty(true);
				if (ph.getRecordCount() == 0) {
					long np = ph.getNextDataPage();
					try {
						if(np > -1) {
							DOMPage next = getCurrentPage(np);
							next.getPageHeader().prevDataPage = -1;
							db.buffer.add(next);
						}
						ph.setNextDataPage(-1);
						ph.setPrevDataPage(-1);
						ph.setDataLength(0);
						//ph.setNextTID((short)0);
						ph.setRecordCount((short) 0);
						p.setDirty(true);
						db.buffer.remove(p);
						db.unlinkPages(p.page);
					} catch (IOException ioe) {
						LOG.warn(ioe);
					}
					page = np;
					offset = 0;
				}
			} catch (LockException e) {
				LOG.warn(e);
			} finally {
				lock.release(lockKey);
			}
		}

		/**
		 *  Sets the to attribute of the DOMFileIterator object
		 *
		 *@param  node  The new to value
		 */
		public void setTo(NodeProxy node) {
			this.node = node;
		}

		/**
		 *  Sets the to attribute of the DOMFileIterator object
		 *
		 *@param  address  The new to value
		 */
		public void setTo(long address) {
			this.startAddress = address;
		}
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    3. Juni 2002
	 */
	private final class DOMFileHeader extends BTreeFileHeader {

		protected LinkedList reserved = new LinkedList();

		/**  Constructor for the DOMFileHeader object */
		public DOMFileHeader() {
		}

		/**
		 *  Constructor for the DOMFileHeader object
		 *
		 *@param  pageCount  Description of the Parameter
		 */
		public DOMFileHeader(long pageCount) {
			super(pageCount);
		}

		/**
		 *  Constructor for the DOMFileHeader object
		 *
		 *@param  pageCount  Description of the Parameter
		 *@param  pageSize   Description of the Parameter
		 */
		public DOMFileHeader(long pageCount, int pageSize) {
			super(pageCount, pageSize);
		}

		/**
		 *  Constructor for the DOMFileHeader object
		 *
		 *@param  pageCount  Description of the Parameter
		 *@param  pageSize   Description of the Parameter
		 *@param  blockSize  Description of the Parameter
		 */
		public DOMFileHeader(long pageCount, int pageSize, byte blockSize) {
			super(pageCount, pageSize, blockSize);
		}

		/**
		 *  Constructor for the DOMFileHeader object
		 *
		 *@param  read             Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public DOMFileHeader(boolean read) throws IOException {
			super(read);
		}

		/**
		 *  Adds a feature to the ReservedPage attribute of the DOMFileHeader
		 *  object
		 *
		 *@param  page  The feature to be added to the ReservedPage attribute
		 */
		public void addReservedPage(long page) {
			reserved.addFirst(new Long(page));
		}

		/**
		 *  Gets the reservedPage attribute of the DOMFileHeader object
		 *
		 *@return    The reservedPage value
		 */
		public long getReservedPage() {
			if (reserved.size() == 0)
				return -1;
			return ((Long) reserved.removeLast()).longValue();
		}

		/**
		 *  Description of the Method
		 *
		 *@param  raf              Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public void read(java.io.RandomAccessFile raf) throws IOException {
			super.read(raf);
			//lastDataPage = raf.readLong();
			int rp = raf.readInt();
			long l;
			for (int i = 0; i < rp; i++) {
				l = raf.readLong();
				reserved.addFirst(new Long(l));
			}
		}

		/**
		 *  Description of the Method
		 *
		 *@param  raf              Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public void write(java.io.RandomAccessFile raf) throws IOException {
			super.write(raf);
			//raf.writeLong(lastDataPage);
			raf.writeInt(reserved.size());
			Long l;
			for (Iterator i = reserved.iterator(); i.hasNext();) {
				l = (Long) i.next();
				raf.writeLong(l.longValue());
			}
		}
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    3. Juni 2002
	 */
	private final class DOMFilePageHeader extends BTreePageHeader {
		protected int dataLen = 0;
		protected long nextDataPage = -1;
		protected long prevDataPage = -1;
		protected short tid = 0;
		protected short records = 0;

		/**  Constructor for the DOMFilePageHeader object */
		public DOMFilePageHeader() {
			super();
		}

		/**
		 *  Constructor for the DOMFilePageHeader object
		 *
		 *@param  dis              Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public DOMFilePageHeader(DataInputStream is) throws IOException {
			super(is);
		}

		/**  Description of the Method */
		public void decRecordCount() {
			--records;
		}

		public short getNextTID() {
			return ++tid;
		}

		public void setNextTID(short tid) {
			this.tid = tid;
		}

		/**
		 *  Gets the dataLength attribute of the DOMFilePageHeader object
		 *
		 *@return    The dataLength value
		 */
		public int getDataLength() {
			return dataLen;
		}

		/**
		 *  Gets the nextDataPage attribute of the DOMFilePageHeader object
		 *
		 *@return    The nextDataPage value
		 */
		public long getNextDataPage() {
			return nextDataPage;
		}

		public long getPrevDataPage() {
			return prevDataPage;
		}

		/**
		 *  Gets the recordCount attribute of the DOMFilePageHeader object
		 *
		 *@return    The recordCount value
		 */
		public short getRecordCount() {
			return records;
		}

		/**  Description of the Method */
		public void incRecordCount() {
			records++;
		}

		/**
		 *  Description of the Method
		 *
		 *@param  dis              Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public void read(DataInputStream dis) throws IOException {
			super.read(dis);
			records = dis.readShort();
			dataLen = dis.readInt();
			nextDataPage = dis.readLong();
			prevDataPage = dis.readLong();
			tid = dis.readShort();
		}

		/**
		 *  Sets the dataLength attribute of the DOMFilePageHeader object
		 *
		 *@param  len  The new dataLength value
		 */
		public void setDataLength(int len) {
			dataLen = len;
		}

		/**
		 *  Sets the nextDataPage attribute of the DOMFilePageHeader object
		 *
		 *@param  page  The new nextDataPage value
		 */
		public void setNextDataPage(long page) {
			nextDataPage = page;
		}

		public void setPrevDataPage(long page) {
			prevDataPage = page;
		}

		/**
		 *  Sets the recordCount attribute of the DOMFilePageHeader object
		 *
		 *@param  recs  The new recordCount value
		 */
		public void setRecordCount(short recs) {
			records = recs;
		}

		/**
		 *  Description of the Method
		 *
		 *@param  dos              Description of the Parameter
		 *@exception  IOException  Description of the Exception
		 */
		public void write(DataOutputStream dos) throws IOException {
			super.write(dos);
			dos.writeShort(records);
			dos.writeInt(dataLen);
			dos.writeLong(nextDataPage);
			dos.writeLong(prevDataPage);
			dos.writeShort(tid);
			//            if(dataLen == 0) {
			//                LOG.debug("dataLen == 0");
			//                Thread.dumpStack();
			//            }
		}
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    3. Juni 2002
	 */
	private final class DOMPage {

		byte[] data;
		int len = 0;
		Page page;
		int refCount = 0;
		boolean saved = true;

		/**  Constructor for the DOMPage object */
		public DOMPage() {
			page = createNewPage();
			data = new byte[fileHeader.getWorkSize()];
			len = 0;
		}

		/**
		 *  Constructor for the DOMPage object
		 *
		 *@param  pos  Description of the Parameter
		 */
		public DOMPage(long pos) {
			try {
				page = getPage(pos);
				load(page);
			} catch (IOException ioe) {
				LOG.debug(ioe);
				ioe.printStackTrace();
			}
		}

		/**
		 *  Constructor for the DOMPage object
		 *
		 *@param  page  Description of the Parameter
		 */
		public DOMPage(Page page) {
			this.page = page;
			load(page);
		}

		/**  Description of the Method */
		public void decRefCount() {
			refCount--;
		}

		/**
		 *  Gets the pageHeader attribute of the DOMPage object
		 *
		 *@return    The pageHeader value
		 */
		public DOMFilePageHeader getPageHeader() {
			return (DOMFilePageHeader) page.getPageHeader();
		}

		/**
		 *  Gets the pageNum attribute of the DOMPage object
		 *
		 *@return    The pageNum value
		 */
		public long getPageNum() {
			return page.getPageNum();
		}

		/**
		 *  Gets the refCount attribute of the DOMPage object
		 *
		 *@return    The refCount value
		 */
		public int getRefCount() {
			return refCount;
		}

		/**  Description of the Method */
		public void incRefCount() {
			refCount++;
		}

		/**
		 *  Gets the dirty attribute of the DOMPage object
		 *
		 *@return    The dirty value
		 */
		public boolean isDirty() {
			return !saved;
		}

		public void setDirty(boolean dirty) {
			saved = !dirty;
			page.getPageHeader().setDirty(dirty);
		}

		private void load(Page page) {
			try {
				data = page.read();
				DOMFilePageHeader ph = (DOMFilePageHeader) page.getPageHeader();
				len = ph.getDataLength();
				if (data.length == 0) {
					LOG.debug(
						"page " + page.getPageNum() + " data length == 0");
					return;
				}
			} catch (IOException ioe) {
				LOG.debug(ioe);
				ioe.printStackTrace();
			}
			saved = true;
		}

		/**  Description of the Method */
		public void write() {
			if (page == null)
				return;
			try {
				DOMFilePageHeader ph = (DOMFilePageHeader) page.getPageHeader();
				if (!ph.isDirty())
					return;
				//ph.setDataLength(len);
				ph.setRecordLen(len);
				Value value = new Value(data);
				writeValue(page, value);
				//page.write();
			} catch (IOException ioe) {
				LOG.error(ioe);
			}
		}
	}

	/**
	 *  Cache for data pages. Pages are put on top of a stack. If the stack size
	 *  exceeds blockBuffers, the last page in the stack will be removed and
	 *  saved to disk. When a page is removed, it's dirty flag is check to
	 *  determine if the page needs to be saved. If the page is dirty, the page
	 *  is saved.
	 *
	 *@author     Wolfgang Meier <meier@ifs.tu-darmstadt.de>
	 *@created    25. Mai 2002
	 */
	protected class ClockPageBuffer {
		protected int blockBuffers;
		protected int hits = 0;
		//protected TLongObjectHashMap map;
		protected Long2ObjectLinkedOpenHashMap map;
		protected int misses = 0;

		//protected LinkedList queue = new LinkedList();

		/**
		 *  Constructor for the PageBuffer object
		 *
		 *@param  blockBuffers  Description of the Parameter
		 */
		public ClockPageBuffer(int blockBuffers) {
			this.blockBuffers = blockBuffers;
			//map = new TLongObjectHashMap(blockBuffers);
			map = new Long2ObjectLinkedOpenHashMap(blockBuffers);
		}

		/**  Constructor for the PageBuffer object */
		public ClockPageBuffer() {
			this(64);
		}

		public void add(DOMPage page) {
			add(page, 1);
		}
		
		/**
		 *  Description of the Method
		 *
		 *@param  page  Description of the Parameter
		 */
		public void add(DOMPage page, int initialRefCount) {
			if (map.containsKey(page.page.getPageNum())) {
				page.incRefCount();
				return;
			}
			while (map.size() > blockBuffers) {
				boolean removed = false;
				while (!removed) {
					for (Iterator i = map.values().iterator(); i.hasNext();) {
						DOMPage old = (DOMPage) i.next();
						old.decRefCount();
						if (old.getRefCount() < 1
							&& old.getPageNum() != page.getPageNum()) {
							//i.remove();
							map.remove(old.page.getPageNum());
							if (old.isDirty()) {
								old.write();
							}
							removed = true;
							break;
						}
					}
				}
			}
			//queue.add(page);
			page.refCount = initialRefCount;
			map.put(page.page.getPageNum(), page);
		}

		/**  Description of the Method */
		public void flush() {
			DOMPage page;
			for (Iterator i = map.values().iterator(); i.hasNext();) {
				page = (DOMPage) i.next();
				if (page.isDirty())
					page.write();
			}
		}

		public void clear() {
			flush();
			//queue.clear();
			map.clear();
		}

		/**
		 *  Description of the Method
		 *
		 *@param  page  Description of the Parameter
		 *@return       Description of the Return Value
		 */
		public DOMPage get(Page page) {
			return get(page.getPageNum());
		}

		/**
		 *  Description of the Method
		 *
		 *@param  pnum  Description of the Parameter
		 *@return       Description of the Return Value
		 */
		public DOMPage get(long pnum) {
			DOMPage page = (DOMPage) map.get(pnum);
			if (page == null)
				misses++;
			else
				hits++;
			return page;
		}

		/**
		 *  Description of the Method
		 *
		 *@param  page  Description of the Parameter
		 */
		public void remove(DOMPage page) {
			map.remove(page.page.getPageNum());
		}

		public void printStatistics() {
			StringBuffer buf = new StringBuffer();
			buf.append("dom.dbx DATA ").append(blockBuffers);
			buf.append(" / ").append(hits);
			buf.append(" / ").append(misses);
			LOG.info(buf.toString());
		}
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    3. Juni 2002
	 */
	private final class FindCallback implements BTreeCallback {
		/**  Description of the Field */
		public final static int KEYS = 1;

		/**  Description of the Field */
		public final static int VALUES = 0;
		int mode = VALUES;

		ArrayList values = new ArrayList();

		/**
		 *  Constructor for the FindCallback object
		 *
		 *@param  mode  Description of the Parameter
		 */
		public FindCallback(int mode) {
			this.mode = mode;
		}

		/**
		 *  Gets the values attribute of the FindCallback object
		 *
		 *@return    The values value
		 */
		public ArrayList getValues() {
			return values;
		}

		/**
		 *  Description of the Method
		 *
		 *@param  value    Description of the Parameter
		 *@param  pointer  Description of the Parameter
		 *@return          Description of the Return Value
		 */
		public boolean indexInfo(Value value, long pointer) {
			switch (mode) {
				case VALUES :
					RecordPos rec = findValuePosition(pointer);
					short l =
						ByteConversion.byteToShort(rec.page.data, rec.offset);
					int dataStart = rec.offset + 2;
					//int l = (int) VariableByteCoding.decode( page.data, offset );
					//int dataStart = VariableByteCoding.getSize( l );
					values.add(new Value(rec.page.data, dataStart, l));
					return true;
				case KEYS :
					values.add(value);
					return true;
			}
			return false;
		}
	}

	/**
	 *  Description of the Class
	 *
	 *@author     wolf
	 *@created    3. Juni 2002
	 */
	private final class RangeCallback implements BTreeCallback {

		ArrayList values = new ArrayList();

		/**  Constructor for the RangeCallback object */
		public RangeCallback() {
		}

		/**
		 *  Gets the values attribute of the RangeCallback object
		 *
		 *@return    The values value
		 */
		public ArrayList getValues() {
			return values;
		}

		/**
		 *  Description of the Method
		 *
		 *@param  value    Description of the Parameter
		 *@param  pointer  Description of the Parameter
		 *@return          Description of the Return Value
		 */
		public boolean indexInfo(Value value, long pointer) {
			RecordPos rec = findValuePosition(pointer);
			short l = ByteConversion.byteToShort(rec.page.data, rec.offset);
			int dataStart = rec.offset + 2;
			//int l = (int) VariableByteCoding.decode( page.data, offset );
			//int dataStart = VariableByteCoding.getSize( l ) + offset;
			values.add(new Value(rec.page.data, dataStart, l));
			return true;
		}
	}
	
	public static final String printAddress(long p) {
		return pageFromPointer(p) + ":" + tidFromPointer(p);
	}
}
