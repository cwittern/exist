/*
 * Rename.java - May 1, 2003
 * 
 * @author wolf
 */
package org.exist.xupdate;

import java.util.Iterator;
import java.util.List;

import org.exist.EXistException;
import org.exist.dom.AttrImpl;
import org.exist.dom.Collection;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.ElementImpl;
import org.exist.dom.NodeImpl;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.User;
import org.exist.storage.BrokerPool;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class Rename extends Modification {

	/**
	 * @param pool
	 * @param user
	 * @param selectStmt
	 */
	public Rename(BrokerPool pool, User user, DocumentSet docs, String selectStmt) {
		super(pool, user, docs, selectStmt);
	}

	/* (non-Javadoc)
	 * @see org.exist.xupdate.Modification#process(org.exist.dom.DocumentSet)
	 */
	public long process() throws PermissionDeniedException, EXistException {
		List qr = select(docs);
		NodeList children = content.getChildNodes();
		if (qr == null || children.getLength() == 0)
			return 0;
		DocumentImpl doc;
		Collection collection;
		NodeImpl node;
		ElementImpl parent;
		String newName = children.item(0).getNodeValue();
		int modificationCount = 0;
		for (Iterator i = qr.iterator(); i.hasNext();) {
			node = (NodeImpl) i.next();
			doc = (DocumentImpl) node.getOwnerDocument();
			collection = doc.getCollection();
			if (!collection.getPermissions().validate(user, Permission.UPDATE))
				throw new PermissionDeniedException(
					"write access to collection denied; user=" + user.getName());
			if (!doc.getPermissions().validate(user, Permission.UPDATE))
				throw new PermissionDeniedException("permission denied to update document");
			parent = (ElementImpl)node.getParentNode();
			switch(node.getNodeType()) {
				case Node.ELEMENT_NODE :
					((ElementImpl)node).setNodeName(newName);
					parent.updateChild(node, node);
					modificationCount++;
					break;
				case Node.ATTRIBUTE_NODE :
					((AttrImpl)node).setNodeName(newName);
					parent.updateChild(node, node);
					modificationCount++;
					break;
			}
		}
		return modificationCount;
	}

	/* (non-Javadoc)
	 * @see org.exist.xupdate.Modification#getName()
	 */
	public String getName() {
		return "rename";
	}

}
