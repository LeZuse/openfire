<%--
  -	$RCSfile$
  -	$Revision$
  -	$Date$
  -
  - Copyright (C) 2004 Jive Software. All rights reserved.
  -
  - This software is published under the terms of the GNU Public License (GPL),
  - a copy of which is included in this distribution.
--%>

<%@ page import="org.jivesoftware.util.*,
                 org.jivesoftware.messenger.user.*,
                 java.text.DateFormat,
                 java.util.*,
                 org.jivesoftware.admin.*,
                 org.jivesoftware.messenger.*"
    errorPage="error.jsp"
%>

<%@ taglib uri="core" prefix="c"%>

<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager" />
<jsp:useBean id="userData" class="org.jivesoftware.messenger.user.spi.UserPrivateData" />

<%  // Get parameters //
    boolean cancel = request.getParameter("cancel") != null;
    boolean delete = request.getParameter("delete") != null;
    boolean email = request.getParameter("email") != null;
    boolean password = request.getParameter("password") != null;
    String username = ParamUtils.getParameter(request,"username");

    // Handle a cancel
    if (cancel) {
        response.sendRedirect("user-summary.jsp");
        return;
    }

    // Handle a delete
    if (delete) {
        response.sendRedirect("user-delete.jsp?username=" + username);
        return;
    }

    // Handle an email
    if (email) {
        response.sendRedirect("user-email.jsp?username=" + username);
        return;
    }

    // Handle an email
    if (password) {
        response.sendRedirect("user-password.jsp?username=" + username);
        return;
    }

    // Load the user object
    User user = null;
    try {
        user = webManager.getUserManager().getUser(username);
    }
    catch (UserNotFoundException unfe) {
        user = webManager.getUserManager().getUser(username);
    }

    PresenceManager presenceManager = webManager.getPresenceManager();

    // Date formatter for dates
    DateFormat formatter = DateFormat.getDateInstance(DateFormat.MEDIUM);
%>

<%
 // Get a private data manager //
  final PrivateStore privateStore = webManager.getPrivateStore();
  userData.setState( user.getUsername(), privateStore );
  String nickname = userData.getProperty( "nickname" );
    if(nickname == null){
        nickname = "";
    }
%>

<jsp:useBean id="pageinfo" scope="request" class="org.jivesoftware.admin.AdminPageBean" />
<%  // Title of this page and breadcrumbs
    String title = "User Properties";
    pageinfo.setTitle(title);
    pageinfo.getBreadcrumbs().add(new AdminPageBean.Breadcrumb("Main", "main.jsp"));
    pageinfo.getBreadcrumbs().add(new AdminPageBean.Breadcrumb(title, "user-properties.jsp"));
    pageinfo.setSubPageID("user-properties");
    pageinfo.setExtraParams("username="+username);
%>
<jsp:include page="top.jsp" flush="true" />
<jsp:include page="title.jsp" flush="true" />

<p>
Below is a summary of user properties. To edit properties, click the "Edit" button below.
</p>

<%  if (request.getParameter("success") != null) { %>

    <div class="jive-success">
    <table cellpadding="0" cellspacing="0" border="0">
    <tbody>
        <tr><td class="jive-icon"><img src="images/success-16x16.gif" width="16" height="16" border="0"></td>
        <td class="jive-icon-label">
        New user created successfully.
        </td></tr>
    </tbody>
    </table>
    </div><br>

<%  } %>

<div class="jive-table">
<table cellpadding="0" cellspacing="0" border="0" width="100%">
<thead>
    <tr>
        <th colspan="2">
            User Properties
        </th>
    </tr>
</thead>
<tbody>
    <tr>
        <td class="c1">
            Username:
        </td>
        <td>
            <%= user.getUsername() %>
        </td>
    </tr>
    <tr>
        <td class="c1">
            Status:
        </td>
        <td>
            <%  if (presenceManager.isAvailable(user)) {
                    Presence presence = presenceManager.getPresence(user);
            %>
                <% if (presence.getShow() == Presence.SHOW_NONE) { %>
                    <img src="images/user-green-16x16.gif" width="16" height="16" border="0" alt="Available">
                    Available
                <% } %>
                <% if (presence.getShow() == Presence.SHOW_CHAT) { %>
                    <img src="images/user-green-16x16.gif" width="16" height="16" border="0" alt="Available to Chat">
                    Available to Chat
                <% } %>
                <% if (presence.getShow() == Presence.SHOW_AWAY) { %>
                    <img src="images/user-yellow-16x16.gif" width="16" height="16" border="0" alt="Away">
                    Away
                <% } %>
                <% if (presence.getShow() == Presence.SHOW_XA) { %>
                    <img src="images/user-yellow-16x16.gif" width="16" height="16" border="0" alt="Extended Away">
                    Extended Away
                <% } %>
                <% if (presence.getShow() == Presence.SHOW_DND) { %>
                    <img src="images/user-red-16x16.gif" width="16" height="16" border="0" alt="Do not Disturb">
                    Do not Disturb
                <% } %>

            <%  } else { %>

                <img src="images/user-clear-16x16.gif" width="16" height="16" border="0" alt="Offline">
                (Offline)

            <%  } %>
        </td>
    </tr>
    <tr>
        <td class="c1">
            Name:
        </td>
        <td>
            <%  if (user.getInfo().getName() == null || "".equals(user.getInfo().getName())) { %>

                <span style="color:#999">
                <i>Not set.</i>
                </span>

            <%  } else { %>

                <%= user.getInfo().getName() %>

            <%  } %>
        </td>
    </tr>
    <tr>
        <td class="c1">
            Email:
        </td>
        <td>
            <%  if (user.getInfo().getEmail() == null || "".equals(user.getInfo().getEmail())) { %>

                <span style="color:#999">
                <i>Not set.</i>
                </span>

            <%  } else { %>

                <a href="mailto:<%= user.getInfo().getEmail() %>"><%= user.getInfo().getEmail() %></a>

            <%  } %>
        </td>
    </tr>
    <tr>
        <td class="c1">
            Registered:
        </td>
        <td>
            <%= formatter.format(user.getInfo().getCreationDate()) %>
        </td>
    </tr>
</tbody>
</table>
</div>

<br><br>

<form action="user-edit.jsp">
<input type="submit" value="Edit Properties">
</form>

<jsp:include page="bottom.jsp" flush="true" />