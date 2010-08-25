package org.dcache.webadmin.view.panels.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.wicket.authorization.strategies.role.Roles;
import org.apache.wicket.authorization.strategies.role.metadata.MetaDataRoleAuthorizationStrategy;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.Link;
import org.dcache.webadmin.view.pages.basepage.BasePage;
import org.dcache.webadmin.view.pages.cellservices.CellServices;
import org.dcache.webadmin.view.pages.dcacheservices.DCacheServices;
import org.dcache.webadmin.view.pages.poolgroupview.PoolGroupView;
import org.dcache.webadmin.view.pages.poollist.PoolList;
import org.dcache.webadmin.view.pages.poolqueues.PoolQueues;
import org.dcache.webadmin.view.pages.infoxml.InfoXml;
import org.dcache.webadmin.view.pages.pooladmin.PoolAdmin;
import org.dcache.webadmin.view.pages.celladmin.CellAdmin;
import org.dcache.webadmin.view.panels.basepanel.BasePanel;
import org.dcache.webadmin.view.util.CustomLink;
import org.dcache.webadmin.view.util.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * reusable navigation-panel. Each new link needs to be added to the buildLinks
 * method and a new Property in the BasicNavigationPanel.properties with the
 * correct index behind it (see in the File for the previous ones as examples)
 * @author jans
 */
public class BasicNavigationPanel extends BasePanel {

    private Class _currentPage;
    private static final String LINK_TITLE_PROPERTY_NAME = "link";
    private static List<Class> _linkList = new ArrayList<Class>(Arrays.asList(
            DCacheServices.class, CellServices.class, PoolList.class,
            PoolQueues.class, PoolGroupView.class, PoolAdmin.class,
            CellAdmin.class, InfoXml.class));
    private static List<Class> _adminLinks = new ArrayList<Class>(Arrays.asList(
            PoolAdmin.class, CellAdmin.class));
    private static final Logger _log = LoggerFactory.getLogger(BasicNavigationPanel.class);

    public BasicNavigationPanel(String id, Class currentPage) {
        super(id);
        _currentPage = currentPage;
        _log.debug(currentPage.getSimpleName());
        add(new LinkListView<Class>("linkList", _linkList));
    }

    private class LinkListView<T> extends ListView<T> {

        public LinkListView(String id, List<? extends T> items) {
            super(id, items);
        }

        @Override
        protected void populateItem(ListItem item) {
            Class targetPage = (Class) item.getModelObject();
            CustomLink link = new CustomLink("link", targetPage);
            handleAdminPage(targetPage, link, item);
            setLinkTitle(link, item.getIndex());
            handleActivePage(targetPage, item);
            item.add(link);
        }

        private void handleAdminPage(Class targetPage, Link link, ListItem item) {
            if (isAdminPage(targetPage)) {
                MetaDataRoleAuthorizationStrategy.authorize(link, ENABLE, Role.ADMIN);
                if (!isUserAdmin()) {
                    addAdminOnlyTooltip(item);
                }
            }
        }

        private boolean isAdminPage(Class targetPage) {
            return _adminLinks.contains(targetPage);
        }

        private void addAdminOnlyTooltip(ListItem item) {
            item.add(new SimpleAttributeModifier("title", (String) getStringResource(
                    "tooltip.AdminOnly").getObject()));
        }

        private boolean isUserAdmin() {
            return ((BasePage) getPage()).getWebadminSession().
                    hasAnyRole(new Roles(Role.ADMIN));
        }

        private void setLinkTitle(CustomLink link, int linkNumber) {
            link.add(new Label("linkMessage", getStringResource(
                    LINK_TITLE_PROPERTY_NAME + linkNumber)));
        }

        private void handleActivePage(Class targetPage, ListItem item) {
            if (isActivePage(targetPage)) {
                addActiveAttribute(item);
            }
        }

        private boolean isActivePage(Class targetPage) {
            return targetPage.equals(_currentPage);
        }

        private void addActiveAttribute(ListItem item) {
            item.add(new SimpleAttributeModifier("class", "active"));
        }
    }
}
