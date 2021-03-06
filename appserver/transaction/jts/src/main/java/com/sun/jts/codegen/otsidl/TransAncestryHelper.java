/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.jts.codegen.otsidl;


/**
* com/sun/jts/codegen/otsidl/TransAncestryHelper.java .
* Generated by the IDL-to-Java compiler (portable), version "3.1"
* from com/sun/jts/ots.idl
* Tuesday, February 5, 2002 12:57:23 PM PST
*/

abstract public class TransAncestryHelper
{
  private static String  _id = "IDL:otsidl/TransAncestry:1.0";

  public static void insert (org.omg.CORBA.Any a, org.omg.CosTransactions.TransIdentity[] that)
  {
    org.omg.CORBA.portable.OutputStream out = a.create_output_stream ();
    a.type (type ());
    write (out, that);
    a.read_value (out.create_input_stream (), type ());
  }

  public static org.omg.CosTransactions.TransIdentity[] extract (org.omg.CORBA.Any a)
  {
    return read (a.create_input_stream ());
  }

  private static org.omg.CORBA.TypeCode __typeCode = null;
  synchronized public static org.omg.CORBA.TypeCode type ()
  {
    if (__typeCode == null)
    {
      __typeCode = org.omg.CosTransactions.TransIdentityHelper.type ();
      __typeCode = org.omg.CORBA.ORB.init ().create_sequence_tc (0, __typeCode);
      __typeCode = org.omg.CORBA.ORB.init ().create_alias_tc (com.sun.jts.codegen.otsidl.TransAncestryHelper.id (), "TransAncestry", __typeCode);
    }
    return __typeCode;
  }

  public static String id ()
  {
    return _id;
  }

  public static org.omg.CosTransactions.TransIdentity[] read (org.omg.CORBA.portable.InputStream istream)
  {
    org.omg.CosTransactions.TransIdentity value[] = null;
    int _len0 = istream.read_long ();
    value = new org.omg.CosTransactions.TransIdentity[_len0];
    for (int _o1 = 0;_o1 < value.length; ++_o1)
      value[_o1] = org.omg.CosTransactions.TransIdentityHelper.read (istream);
    return value;
  }

  public static void write (org.omg.CORBA.portable.OutputStream ostream, org.omg.CosTransactions.TransIdentity[] value)
  {
    ostream.write_long (value.length);
    for (int _i0 = 0;_i0 < value.length; ++_i0)
      org.omg.CosTransactions.TransIdentityHelper.write (ostream, value[_i0]);
  }

}
