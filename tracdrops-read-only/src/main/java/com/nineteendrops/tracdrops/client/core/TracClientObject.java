package com.nineteendrops.tracdrops.client.core;

/**
 * Created www.19drops.com
 * User: 19drops
 * Date: 23-ago-2009
 * Time: 19:03:09
 * <p/>
 * This material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
public class TracClientObject {

    protected TracInvocationObjectFactory tracInvocationObjectFactory = null;

    public TracClientObject(TracInvocationObjectFactory tracInvocationObjectFactory) {
        this.tracInvocationObjectFactory = tracInvocationObjectFactory;
    }

    protected Object getTracInvocationObject(Class typeClientObject){

        return tracInvocationObjectFactory.newInstance(typeClientObject);

    }

}
