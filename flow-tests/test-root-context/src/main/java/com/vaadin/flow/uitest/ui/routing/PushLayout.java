/*
 * Copyright 2000-2018 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.uitest.ui.routing;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLayout;

@Push
@Route("com.vaadin.flow.uitest.ui.PushLayout")
public class PushLayout extends Div
        implements RouterLayout, BeforeEnterObserver {

    public static String FORWARD_PATH = "forward-no-route";

    public PushLayout() {
        setId("push-layout");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (FORWARD_PATH.equals(event.getLocation().getPath())) {
            event.forwardTo(ForwardPage.class);
        }
    }

}