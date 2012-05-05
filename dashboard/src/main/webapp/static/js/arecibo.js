/*
 * Copyright 2010-2012 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

window.Arecibo = {
   namespace: function(namespace, obj) {
       var parts = namespace.split('.');
       var parent = window.Arecibo;

       for(var i = 1, length = parts.length; i < length; i++) {
           currentPart = parts[i];
           parent[currentPart] = parent[currentPart] || {};
           parent = parent[currentPart];
       }

       return parent;
   },

   keys: function(objj) {
       var keys = [];
       for (var key in obj) keys.push(key);
       return keys;
   }
};

// Main routine executed at page load time
function setupAreciboUI() {
    // UI setup (Ajax handlers, etc.)
    initializeUI();
    setupDateTimePickers();

    // Retrieve user's last input and populate the input fields
    try {
        samplesStartSelector().val(localStorage.getItem("arecibo_latest_samples_start_lookup"));
        samplesEndSelector().val(localStorage.getItem("arecibo_latest_samples_end_lookup"));
        window.arecibo.hosts_selected = JSON.parse(localStorage.getItem("arecibo_latest_hosts"));
        window.arecibo.sample_kinds_selected = JSON.parse(localStorage.getItem("arecibo_latest_sample_kinds"));
    } catch (e) { /* Ignore quota issues, non supported Browsers, etc. */ }

    // Setup the Graph button
    $("#crunch").click(function (event) {
        // Store locally the latest search
        var samples_start_lookup = samplesStartSelector().val();
        var samples_end_lookup = samplesEndSelector().val();
        try {
            localStorage.setItem("arecibo_latest_samples_start_lookup", samples_start_lookup);
            localStorage.setItem("arecibo_latest_samples_end_lookup", samples_end_lookup);
        } catch (e) { /* Ignore quota issues, non supported Browsers, etc. */ }

        var errorMessage = new Arecibo.InputForm.Validations().validateInput();
        if (errorMessage) {
            alert(errorMessage);
        } else {
            window.location = buildGraphURL();
        }
        // Don't refresh the page
        event.preventDefault();
    });

    // Create en empty sample kinds tree as placeholder if there was no sample kind previously selected
    populateSampleKindsTree([]);

    // Update the hosts tree (and the summary box for hosts if any is selected), and potentially load the sample kinds tree
    updateHostsTree();

    // Update the summary box for sample kinds (if any is selected)
    findSelectedSampleKinds();
    updateSampleKindsSelectedSummary(window.arecibo.sample_kinds_selected);
};

// Return the selector for the samples start input
function samplesStartSelector() {
    return $('#samples_start');
}

// Return the selector for the samples end input
function samplesEndSelector() {
    return $('#samples_end');
}

function updateHostsTree() {
    callArecibo('/rest/1.0/hosts', 'populateHostsTree');
}

function populateHostsTree(hosts) {
    // Order by core type and hostName alphabetically
    hosts.sort(function(a, b) {
            var x = a['coreType']; var y = b['coreType'];
            if (x == y) {
                x = a['hostName']; y = b['hostName'];
            }

            return ((x < y) ? -1 : ((x > y) ? 1 : 0));
        });

    // Create the tree
    $("#hosts_tree").dynatree({
        onSelect: function(flag, node) {
            hostsSelected();
        },
        checkbox: true,
        selectMode: 3
    });

    // Add the nodes
    var selectedHostsSet = Set.makeSet(window.arecibo.hosts_selected, 'hostName');
    var rootNode = $("#hosts_tree").dynatree("getRoot");
    var children = {};
    for (var i in hosts) {
        var host = hosts[i];

        if (!host.coreType) {
            //rootNode.addChild({
            //    title: host.hostName
            //});
        }
        else {
            if (children[host.coreType] === undefined) {
                children[host.coreType] = rootNode.addChild({
                        title: host.coreType,
                        isFolder: true,
                        icon: false,
                        hideCheckbox: false,
                        expand: false,
                        select: false
                });
            }

            var selected = Set.contains(selectedHostsSet, host.hostName);
            var childNode = children[host.coreType];

            childNode.addChild({
                title: host.hostName,
                hideCheckbox: false,
                icon: false,
                select: selected
            });

            // If at least one child node is selected, expand the father
            if (selected) {
                // Note! This needs to happen after the child is added to the father
                childNode.expand(true);
            } else {
                // If at least one child node is not selected, don't select the father
                // TODO Unfortunately, the following will deselect ALL children
                //childNode.select(false);
            }
        }
    }

    // Trigger a sample kinds tree update: this will display the sample kinds tree with previous values, if any
    // and update the summary box for hosts
    hostsSelected();
}

// Find all selected nodes in the hosts tree
function findSelectedHosts() {
    return $("#hosts_tree").dynatree("getTree").getSelectedNodes();
}

// Find all selected nodes in the sample kinds tree
function findSelectedSampleKinds() {
    return $("#sample_kinds_tree").dynatree("getTree").getSelectedNodes();
}

// Find all selected hosts and build the associated query parameter for the dashboard
// This will also set window.arecibo.hosts_selected to a list of tuples (hostName, category)
function buildHostsParamsFromTree() {
    var uri = '';
    var tree = findSelectedHosts();
    window.arecibo.hosts_selected = [];

    for (var i in tree) {
        var node = tree[i];
        if (node.hasSubSel) {
            continue;
        } else {
            if (window.arecibo.hosts_selected.length > 0) {
                uri += '&';
            }

            var hostName = node.data.title;
            var category = null;
            if (node.parent) {
                category = node.parent.data.title;
            }

            uri += 'host=' + node.data.title;
            window.arecibo.hosts_selected.push({hostName: hostName, category: category});
        }
    }

    return uri;
}

// Find all selected sample kinds and build the associated query parameter for the dashboard
// This will also set window.arecibo.sample_kinds_selected to a list of tuples (sampleKind, sampleCategory)
function buildCategoryAndSampleKindParamsFromTree() {
    var uri = '';
    var tree = findSelectedSampleKinds();
    window.arecibo.sample_kinds_selected = [];

    for (var i in tree) {
        var node = tree[i];
        if (node.hasSubSel) {
            continue;
        } else {
            if (window.arecibo.sample_kinds_selected.length > 0) {
                uri += '&';
            }
            uri += 'category_and_sample_kind=';

            var sampleKind = node.data.title;
            var sampleCategory = null;

            // Check if it's a super group
            if (isSuperGroup(sampleKind)) {
                // Yup!
                sampleCategory = getCategoryFromSuperGroup(sampleKind);
                sampleKind = getKindFromSuperGroup(sampleKind);
            } else if (node.parent) {
                sampleCategory = node.parent.data.title;
            }

            if (sampleCategory) {
                uri += sampleCategory + ',';
            }
            uri += sampleKind;
            window.arecibo.sample_kinds_selected.push({sampleKind: sampleKind, sampleCategory: sampleCategory});
        }
    }

    return uri;
}

function isSuperGroup(sampleKind) {
    return sampleKind && sampleKind.split('::').length == 2;
}

function getCategoryFromSuperGroup(sampleKind) {
    return sampleKind.split('::')[0];
}

function getKindFromSuperGroup(sampleKind) {
    return sampleKind.split('::')[1];
}

// Update the hosts list summary
function updateHostsSelectedSummary(hosts) {
    $('#hosts_summary_list').html('');
    for (var i in hosts) {
        var host = hosts[i];
        var hostItem = $('<li></li>').html(host.hostName);
        $('#hosts_summary_list').append(hostItem);
    }
}

// Refresh the sample kinds tree
// This is called when a host is (un)selected on the hosts tree. Selecting or unselecting
// another host in the same category does not refresh the sample kinds tree
function hostsSelected() {
    // Build the uri for the dashboard
    var uri = buildHostsParamsFromTree();

    // Update the summary box
    updateHostsSelectedSummary(window.arecibo.hosts_selected);

    // Remember selected nodes for the next page load
    try {
        localStorage.setItem("arecibo_latest_hosts", JSON.stringify(window.arecibo.hosts_selected));
    } catch (e) { /* Ignore quota issues, non supported Browsers, etc. */ }

    // Verify if we need to update the sample kinds tree or not
    var categoriesSelected = Set.makeSet(window.arecibo.hosts_selected, 'category');
    if (Set.equals(categoriesSelected, window.arecibo.categories_selected)) {
        return false;
    } else {
        window.arecibo.categories_selected = categoriesSelected;
    }

    try {
        $("#sample_kinds_tree").dynatree("getRoot").removeChildren();
    } catch(e){
        // Ignore if the tree was empty
    }

    if (!uri) {
        return false;
    } else {
        callArecibo('/rest/1.0/sample_kinds?' + uri, 'populateSampleKindsTree');
        return false;
    }
}

function populateSampleKindsTree(kinds) {
    // Order by eventCategory alphabetically
    kinds.sort(function(a, b) {
            var x = a['eventCategory'];
            var y = b['eventCategory'];
            var isSuperGroupX = a.sampleKinds ? isSuperGroup(a.sampleKinds[0]) : false;
            var isSuperGroupY = b.sampleKinds ? isSuperGroup(b.sampleKinds[0]) : false;

            // Super groups at the top
            if (isSuperGroupX && !isSuperGroupY) {
                return -1;
            } else if (!isSuperGroupX && isSuperGroupY) {
                return 1;
            } else {
                return ((x < y) ? -1 : ((x > y) ? 1 : 0));
            }
        });

    $("#sample_kinds_tree").dynatree({
        onSelect: function(flag, node) {
            sampleKindsSelected();
        },
        checkbox: true,
        selectMode: 3
    });

    // Add the nodes
    var selectedSamplesSet = Set.makeSet(window.arecibo.sample_kinds_selected, 'sampleKind');
    var rootNode = $("#sample_kinds_tree").dynatree("getRoot");
    var children = {};
    for (var i in kinds) {
        var superGroup = false;
        var category = kinds[i];

        // Add the father
        var sampleCategory = category.eventCategory;
        var childNode = rootNode.addChild({
                title: sampleCategory,
                isFolder: true,
                icon: false,
                hideCheckbox: false,
                expand: false,
                select: false
        });

        var sampleKinds = category.sampleKinds;
        // Order by sample kind alphabetically
        sampleKinds.sort();
        for (var j in sampleKinds) {
            var kind = sampleKinds[j];
            var selected = Set.contains(selectedSamplesSet, kind);
            // We need to check if the sample categories names match as well. In contrary to hostnames,
            // sample kind names are not unique (e.g. memoryPoolUsed)
            if (selected) {
                selected = false;
                for (var i = 0; i < window.arecibo.sample_kinds_selected.length; i++) {
                    var item = window.arecibo.sample_kinds_selected[i];
                    if (item.sampleKind == kind && item.sampleCategory == sampleCategory) {
                        selected = true;
                        break;
                    }
                }
            }

            childNode.addChild({
                title: kind,
                hideCheckbox: false,
                icon: false,
                select: selected
            });
            // If at least one child node is selected, expand the father
            if (selected) {
                // Note! This needs to happen after the child is added to the father
                childNode.expand(true);
            }

            if (isSuperGroup(kind) && !superGroup) {
                childNode.data.title = '<span style="color: red;">' + childNode.data.title + '</span>';
                childNode.render();
                // Render the node once
                superGroup = true;
            }
        }
    }
}

// Update the sample kinds list summary
function updateSampleKindsSelectedSummary(kinds) {
    $('#sample_kinds_summary_list').html('');
    for (var i in kinds) {
        var kind = kinds[i];
        var kindItem = $('<li></li>').html(kind.sampleCategory + '::' + kind.sampleKind);
        $('#sample_kinds_summary_list').append(kindItem);
    }
}

// This is called when a sample kind is (un)selected on the sample kinds tree
function sampleKindsSelected() {
    // Find all selected nodes and build the uri for the dashboard
    buildCategoryAndSampleKindParamsFromTree();

    // Update the summary box
    updateSampleKindsSelectedSummary(window.arecibo.sample_kinds_selected);

    // Remember selected nodes for the next page load
    try {
        localStorage.setItem("arecibo_latest_sample_kinds", JSON.stringify(window.arecibo.sample_kinds_selected));
    } catch (e) { /* Ignore quota issues, non supported Browsers, etc. */ }

    return false;
}

function buildGraphURL() {
    var from = new Date(samplesStartSelector().val());
    var to = new Date(samplesEndSelector().val());
    var hosts_url = buildHostsParamsFromTree();
    var sample_kinds_url = buildCategoryAndSampleKindParamsFromTree();

    var nb_samples = 500;

    var uri = '/static/graph.html?' +
                hosts_url + '&' +
                sample_kinds_url + '&' +
                'from=' + ISODateString(from) + '&' +
                'to=' + ISODateString(to) + '&' +
                'output_count=' + nb_samples;

    return uri;
}

/*
 * Setup the datetime widgets for start/end
 */
function setupDateTimePickers() {
    samplesStartSelector().datetimepicker({
        dateFormat: $.datepicker.RFC_2822,
        timeFormat: 'hh:mm',
        showTimezone: false,
        hourGrid: 4,
        minuteGrid: 10,
        onClose: function(dateText, inst) {
            var endDateTextBox = samplesEndSelector();
            if (endDateTextBox.val() != '') {
                var testStartDate = new Date(dateText);
                var testEndDate = new Date(endDateTextBox.val());
                if (testStartDate > testEndDate)
                    endDateTextBox.val(dateText);
            }
            else {
                endDateTextBox.val(dateText);
            }
        },
        onSelect: function (selectedDateTime){
            var start = $(this).datetimepicker('getDate');
            samplesEndSelector().datetimepicker('option', 'minDate', new Date(start.getTime()));
        }
    });

    samplesEndSelector().datetimepicker({
        dateFormat: $.datepicker.RFC_2822,
        timeFormat: 'hh:mm',
        showTimezone: false,
        hourGrid: 4,
        minuteGrid: 10,
        onClose: function(dateText, inst) {
            var startDateTextBox = samplesStartSelector();
            if (startDateTextBox.val() != '') {
                var testStartDate = new Date(startDateTextBox.val());
                var testEndDate = new Date(dateText);
                if (testStartDate > testEndDate)
                    startDateTextBox.val(dateText);
            }
            else {
                startDateTextBox.val(dateText);
            }
        },
        onSelect: function (selectedDateTime){
            var end = $(this).datetimepicker('getDate');
            samplesStartSelector().datetimepicker('option', 'maxDate', new Date(end.getTime()));
        }
    });
}
