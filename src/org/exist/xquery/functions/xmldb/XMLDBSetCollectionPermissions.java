/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2004-2009 The eXist Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *  
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *  
 *  $Id$
 */
package org.exist.xquery.functions.xmldb;

import org.apache.log4j.Logger;

import org.exist.dom.QName;
import org.exist.security.Permission;
import org.exist.security.PermissionFactory;
import org.exist.security.User;
import org.exist.xmldb.UserManagementService;
import org.exist.xquery.Cardinality;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.XMLDBException;

/**
 * @author Luigi P. Bai, finder@users.sf.net, 2004
 * @author Wolfgang Meier (wolfgang@exist-db.org)
 *
 */
public class XMLDBSetCollectionPermissions extends XMLDBAbstractCollectionManipulator {
	protected static final Logger logger = Logger.getLogger(XMLDBSetCollectionPermissions.class);
	public final static FunctionSignature signature =
		new FunctionSignature(
			new QName("set-collection-permissions", XMLDBModule.NAMESPACE_URI, XMLDBModule.PREFIX),
            "Sets the permissions of the specified collection. $collection-uri is the collection, which can be specified " +
            "as a simple collection path or an XMLDB URI. $user-id specifies the user which " +
            "will become the owner of the resource, $group-id the group. " +
            "The final argument contains the permissions, specified as an xs:integer value. "+
            "PLEASE REMEMBER that 0755 is 7*64+5*8+5, NOT decimal 755.",
			new SequenceType[] {
                new FunctionParameterSequenceType("collection-uri", Type.STRING, Cardinality.EXACTLY_ONE, "the collection-uri"),
                new FunctionParameterSequenceType("user-id", Type.STRING, Cardinality.EXACTLY_ONE, "the user-id"),
                new FunctionParameterSequenceType("group-id", Type.STRING, Cardinality.EXACTLY_ONE, "the group-id"),
                new FunctionParameterSequenceType("permissions", Type.INTEGER, Cardinality.EXACTLY_ONE, "the permissions"),
			},
            new FunctionReturnSequenceType(Type.ITEM, Cardinality.EMPTY, "empty item sequence"));

	
	public XMLDBSetCollectionPermissions(XQueryContext context) {
		super(context, signature);
	}
	
/* (non-Javadoc)
 * @see org.exist.xquery.BasicFunction#eval(org.exist.xquery.value.Sequence[], org.exist.xquery.value.Sequence)
 *
 */
	
	public Sequence evalWithCollection(Collection collection, Sequence[] args, Sequence contextSequence)
		throws XPathException {
		logger.info("Entering " + XMLDBModule.PREFIX + ":" + getName().getLocalName());
        try {
            UserManagementService ums = (UserManagementService) collection.getService("UserManagementService", "1.0");
            String user = args[1].getStringValue();
            String group = args[2].getStringValue();
            int mode = ((IntegerValue) args[3].convertTo(Type.INTEGER)).getInt();
            
            if (null == user || 0 == user.length()) {
                logger.error("Needs a valid user name, not: " + user);
                logger.info("Exiting " + XMLDBModule.PREFIX + ":" + getName().getLocalName());			

                throw new XPathException(this, "Needs a valid user name, not: " + user);
            }
            if (null == group || 0 == group.length()) {
                logger.error("Needs a valid group name, not: " + group);
                logger.info("Exiting " + XMLDBModule.PREFIX + ":" + getName().getLocalName());			

                throw new XPathException(this, "Needs a valid group name, not: " + group);
            }

            // Must actually get a User object for the Permission...
            Permission perms = PermissionFactory.getPermission(user, group, mode);
            User usr = ums.getUser(user);
            if (usr == null) {
                logger.error("Needs a valid user name, not: " + user);
                logger.info("Exiting " + XMLDBModule.PREFIX + ":" + getName().getLocalName());			
                
                throw new XPathException(this, "Needs a valid user name, not: " + user);
            }
            perms.setOwner(usr);
            
            ums.setPermissions(collection, perms);
        } catch (XMLDBException xe) {
            logger.error("Unable to change collection permissions");
            logger.info("Exiting " + XMLDBModule.PREFIX + ":" + getName().getLocalName());			

            throw new XPathException(this, "Unable to change collection permissions", xe);
        }

        logger.info("Exiting " + XMLDBModule.PREFIX + ":" + getName().getLocalName());		
		return Sequence.EMPTY_SEQUENCE;
	}

}
