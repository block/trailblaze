<#noparse>
    <script type="text/javascript">
        // Code modal functionality
        let codeModal, codeContent, codeModalClose;

        function initializeCodeModal() {
            codeModal = document.getElementById('code-modal');
            codeContent = document.getElementById('code-content');
            codeModalClose = document.querySelector('.code-modal-close');

            codeModalClose.onclick = function () {
                codeModal.style.display = 'none';
            }

            window.onclick = function (event) {
                if (event.target === codeModal) {
                    codeModal.style.display = 'none';
                }
            }
        }

        function showCode(encodedCode, type) {
            if (!codeContent) {
                initializeCodeModal();
            }
            codeContent.textContent = decodeURIComponent(encodedCode);
            codeModal.style.display = 'block';
            document.querySelector('.code-modal-content .copy-button').textContent = `Copy ${type} to Clipboard`;
        }

        function copyCode() {
            const text = codeContent.textContent;
            navigator.clipboard.writeText(text).then(() => {
                const copyButton = document.querySelector('.code-modal-content .copy-button');
                const originalText = copyButton.textContent;
                copyButton.textContent = 'Copied!';
                setTimeout(() => {
                    copyButton.textContent = originalText;
                }, 2000);
            });
        }

        // Add debounce function at the top level
        function debounce(func, wait) {
            let timeout;
            return function executedFunction(...args) {
                const later = () => {
                    clearTimeout(timeout);
                    func(...args);
                };
                clearTimeout(timeout);
                timeout = setTimeout(later, wait);
            };
        }


        let drawScreenshotCircle = function (screenshotId, deviceWidth, deviceHeight, clickX, clickY) {
            function createCircle(img) {
                // Remove any existing circles
                const existingCircle = img.parentElement.querySelector('.click-circle');
                if (existingCircle) {
                    existingCircle.remove();
                }

                let cssDisplayedWidth = img.clientWidth;
                let cssDisplayedHeight = img.clientHeight;

                let percentX = clickX / deviceWidth;
                let percentY = clickY / deviceHeight;

                let circleX = percentX * cssDisplayedWidth;
                let circleY = percentY * cssDisplayedHeight;

                let radius = 10;
                let borderWidth = 2;
                let offset = radius / 2;

                var circle = document.createElement('div');
                circle.className = 'click-circle';
                circle.style.position = 'absolute';
                circle.style.left = (circleX - offset) + 'px';
                circle.style.top = (circleY - offset) + 'px';
                circle.style.width = radius + 'px';
                circle.style.height = radius + 'px';
                circle.style.backgroundColor = 'red';
                circle.style.borderRadius = '50%';
                circle.style.border = borderWidth + 'px solid white';
                circle.style.zIndex = '1000';

                // Store the original click coordinates and device dimensions
                circle.dataset.clickX = clickX;
                circle.dataset.clickY = clickY;
                circle.dataset.deviceWidth = deviceWidth;
                circle.dataset.deviceHeight = deviceHeight;

                img.parentElement.appendChild(circle);
            }

            function redrawCircle(img) {
                const circle = img.parentElement.querySelector('.click-circle');
                if (circle) {
                    const clickX = parseInt(circle.dataset.clickX);
                    const clickY = parseInt(circle.dataset.clickY);
                    const deviceWidth = parseInt(circle.dataset.deviceWidth);
                    const deviceHeight = parseInt(circle.dataset.deviceHeight);
                    createCircle(img);
                }
            }

            new MutationObserver((mutations, obs) => {
                // Find the image and create circle if it exists
                const img = document.getElementById(screenshotId);
                if (img) {
                    if (img.complete) {
                        createCircle(img);
                    } else {
                        img.onload = function () {
                            createCircle(img);
                        };
                    }
                    obs.disconnect(); // stop watching
                }
            }).observe(document.body, {
                childList: true,
                subtree: true
            });

            // Add resize handler for this specific image
            const img = document.getElementById(screenshotId);
            if (img) {
                img.addEventListener('resize', debounce(() => redrawCircle(img), 250));
            }
        };

        // Add global resize handler for all circles
        window.addEventListener('resize', debounce(() => {
            document.querySelectorAll('.click-circle').forEach(circle => {
                const img = circle.parentElement.querySelector('img');
                if (img) {
                    const clickX = parseInt(circle.dataset.clickX);
                    const clickY = parseInt(circle.dataset.clickY);
                    const deviceWidth = parseInt(circle.dataset.deviceWidth);
                    const deviceHeight = parseInt(circle.dataset.deviceHeight);
                    createCircle(img);
                }
            });
        }, 250));

        // Function to show error message
        function showError(message) {
            const errorContainer = document.getElementById('error-container');
            errorContainer.innerHTML = `<div class="error-message">${message}</div>`;
        }

        // Function to format duration
        function formatDuration(seconds) {
            const minutes = Math.floor(seconds / 60);
            const remainingSeconds = Math.floor(seconds % 60);
            return minutes > 0 ? `${minutes}m ${remainingSeconds}s` : `${remainingSeconds}s`;
        }

        // Function to format session ID
        function formatSessionId(sessionId) {
            return sessionId.split('_').pop();
        }

        // Function to format cost
        function formatCost(cost) {
            return `$${cost.toFixed(2)}`;
        }

        // Function to format timestamp
        function formatTimestamp(timestampMs) {
            if (!timestampMs) return '-';
            const date = new Date(timestampMs);
            return date.toLocaleString('en-US', {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
                hour12: true
            });
        }

        // Function to format outcome
        function formatOutcome(outcome) {
            switch (outcome) {
                case 'ObjectiveComplete':
                    return '✅';
                case 'ObjectiveFailed':
                    return '❌';
                case 'MaxCallsLimitReached':
                    return '⏰';
                case 'InProgress':
                    return '⏳';
                default:
                    return '❓';
            }
        }

        // Function to get outcome class
        function getOutcomeClass(outcome) {
            switch (outcome) {
                case 'ObjectiveComplete':
                    return 'status-success';
                case 'ObjectiveFailed':
                case 'MaxCallsLimitReached':
                    return 'status-failure';
                case 'InProgress':
                    return 'status-in-progress';
                default:
                    return '';
            }
        }

        // Function to create screenshot gallery
        function createScreenshotGallery(screenshots) {
            if (!screenshots || screenshots.length === 0) return '';

            return `
                <div class="screenshot-gallery" data-screenshots='${JSON.stringify(screenshots)}'>
                    <img src="${screenshots[0]}" alt="Screenshot" onclick="showScreenshot(this.src)"
                        onmouseenter="startAutoCycle(this.closest('.screenshot-gallery'))"
                        onmouseleave="stopAutoCycle(this.closest('.screenshot-gallery'))">
                    <div class="screenshot-controls">
                        <button class="screenshot-nav prev" onclick="navigateScreenshots(this, -1, event)">←</button>
                        <div class="screenshot-counter">1/${screenshots.length}</div>
                        <button class="screenshot-nav next" onclick="navigateScreenshots(this, 1, event)">→</button>
                    </div>
                </div>
            `;
        }

        // Function to navigate through screenshots
        function navigateScreenshots(button, direction, event) {
            if (event) {
                event.stopPropagation(); // Only stop propagation if event is provided
            }
            const gallery = button.closest('.screenshot-gallery');
            const img = gallery.querySelector('img');
            const counter = gallery.querySelector('.screenshot-counter');
            const screenshots = JSON.parse(gallery.dataset.screenshots);
            const currentSrc = img.src;
            const currentIndex = screenshots.indexOf(currentSrc);
            const newIndex = (currentIndex + direction + screenshots.length) % screenshots.length;

            img.src = screenshots[newIndex];
            counter.textContent = `${newIndex + 1}/${screenshots.length}`;
        }

        // Auto-cycle functionality
        let autoCycleIntervals = new Map();

        function startAutoCycle(gallery) {
            if (autoCycleIntervals.has(gallery)) return;

            const interval = setInterval(() => {
                const nextButton = gallery.querySelector('.screenshot-nav.next');
                navigateScreenshots(nextButton, 1);
            }, 1000);

            autoCycleIntervals.set(gallery, interval);
        }

        function stopAutoCycle(gallery) {
            const interval = autoCycleIntervals.get(gallery);
            if (interval) {
                clearInterval(interval);
                autoCycleIntervals.delete(gallery);
            }
        }

        // Sorting functionality
        let currentSort = {
            column: 1, // Default to Start Time column
            direction: 'asc' // Default to oldest first
        };

        function sortTable(column) {
            const tbody = document.getElementById('sessions-body');
            const rows = Array.from(tbody.getElementsByTagName('tr'));
            const headers = document.getElementsByTagName('th');

            // Remove sort classes from all headers
            Array.from(headers).forEach(header => {
                header.classList.remove('sort-asc', 'sort-desc');
            });

            // Determine sort direction
            if (currentSort.column === column) {
                currentSort.direction = currentSort.direction === 'asc' ? 'desc' : 'asc';
            } else {
                currentSort.column = column;
                currentSort.direction = 'asc';
            }

            // Add sort class to current header
            headers[column].classList.add(`sort-${currentSort.direction}`);

            // Sort the rows
            rows.sort((a, b) => {
                let aValue = a.cells[column].textContent.trim();
                let bValue = b.cells[column].textContent.trim();

                // Special handling for different column types
                switch (column) {
                    case 0: // Outcome
                        aValue = a.querySelector('.outcome-emoji').getAttribute('title');
                        bValue = b.querySelector('.outcome-emoji').getAttribute('title');
                        return currentSort.direction === 'asc' ?
                            aValue.localeCompare(bValue) :
                            bValue.localeCompare(aValue);
                    case 1: // Start Time
                        const aTime = parseInt(a.cells[column].getAttribute('data-timestamp')) || 0;
                        const bTime = parseInt(b.cells[column].getAttribute('data-timestamp')) || 0;
                        return currentSort.direction === 'asc' ?
                            aTime - bTime :
                            bTime - aTime;
                    case 2: // Session
                        aValue = a.querySelector('.session-id').textContent;
                        bValue = b.querySelector('.session-id').textContent;
                        return currentSort.direction === 'asc' ?
                            aValue.localeCompare(bValue) :
                            bValue.localeCompare(aValue);
                    case 3: // Duration
                        aValue = parseFloat(aValue);
                        bValue = parseFloat(bValue);
                        return currentSort.direction === 'asc' ?
                            aValue - bValue :
                            bValue - aValue;
                    case 4: // Cost
                        aValue = parseFloat(aValue.replace('$', ''));
                        bValue = parseFloat(bValue.replace('$', ''));
                        return currentSort.direction === 'asc' ?
                            aValue - bValue :
                            bValue - aValue;
                    case 5: // Status Message
                        return currentSort.direction === 'asc' ?
                            aValue.localeCompare(bValue) :
                            bValue.localeCompare(aValue);
                    default:
                        return 0;
                }
            });

            // Reorder the rows in the table
            rows.forEach(row => tbody.appendChild(row));
        }

        // Function to update the UI with data
        function updateUI(data) {
            try {
                // Update summary statistics
                const completeSessions = data.sessions.filter(s => s.outcome === 'ObjectiveComplete').length;
                const failedSessions = data.sessions.filter(s => s.outcome === 'ObjectiveFailed').length;
                const maxCallsSessions = data.sessions.filter(s => s.outcome === 'MaxCallsLimitReached').length;

                document.getElementById('outcome-complete').textContent = completeSessions;
                document.getElementById('outcome-failed').textContent = failedSessions;
                document.getElementById('outcome-maxcalls').textContent = maxCallsSessions;

                const totalLLMCalls = data.sessions.reduce((sum, session) => sum + session.llmCallCount, 0);
                const totalCost = data.sessions.reduce((sum, session) => sum + (session.totalCostInUsDollars || 0), 0);
                document.getElementById('llm-usage').innerHTML = `${formatCost(totalCost)}<div class="sub-value">${totalLLMCalls} Call${totalLLMCalls === 1 ? '' : 's'}</div>`;

                // Populate sessions table
                const sessionsBody = document.getElementById('sessions-body');
                sessionsBody.innerHTML = ''; // Clear existing content

                data.sessions.forEach((session, idx) => {
                    const row = document.createElement('tr');
                    row.setAttribute('data-row-idx', idx);
                    const uniquePrompts = [...new Set(session.agentTasks?.map(task => task.prompt) || [])];
                    const promptsHtml = uniquePrompts.length > 0 ?
                        `<div class="prompt-list"><div class="prompt-header">${uniquePrompts.length} Agent Task${uniquePrompts.length === 1 ? '' : 's'}:</div>${uniquePrompts.map(prompt =>
                            `<div class="prompt-item">${prompt}</div>`
                        ).join('')}</div>` : '';

                    row.innerHTML = `
                        <td><span class="expand-icon" title="Expand for details" aria-expanded="false">&#9654;</span><span class="${getOutcomeClass(session.outcome)} outcome-emoji" title="${session.outcome || 'Not Found'}">${formatOutcome(session.outcome)}</span></td>
                        <td data-timestamp="${session.sessionStartTimestampMs || 0}">${formatTimestamp(session.sessionStartTimestampMs)}</td>
                        <td><span class="session-id">${session.sessionId}</span></td>
                        <td>${formatDuration(session.sessionDurationSeconds)}</td>
                        <td>${formatCost(session.totalCostInUsDollars || 0)}<span class="llm-calls-text">${session.llmCallCount} call${session.llmCallCount === 1 ? '' : 's'}</span><span class="model-text">${session.llmModelId || '-'}</span></td>
                        <td class="status-message">${promptsHtml}</td>
                        <td>${createScreenshotGallery(session.screenshots)}</td>
                        <td><button class="details-btn" title="View detailed report" onclick="navigateToSession('${session.sessionId}'); event.stopPropagation();">View</button></td>
                    `;
                    // --- Expand/collapse logic ---
                    let hoverTimer = null;
                    const expandIcon = row.querySelector('.expand-icon');
                    // Icon click: lock/unlock expansion
                    expandIcon.addEventListener('click', function (e) {
                        e.stopPropagation();
                        const locked = row.classList.contains('locked-expanded');
                        // If already locked, unlock
                        if (locked) {
                            row.classList.remove('locked-expanded');
                            expandIcon.setAttribute('aria-expanded', 'false');
                            expandIcon.title = 'Expand for details';
                        } else {
                            row.classList.add('locked-expanded');
                            expandIcon.setAttribute('aria-expanded', 'true');
                            expandIcon.title = 'Collapse details';
                        }
                        scrollRowIfNeeded();
                    });
                    // Row click: lock/unlock expansion
                    row.addEventListener('click', function (e) {
                        // Don't handle clicks on navigation buttons or screenshot gallery
                        if (e.target.closest('.screenshot-gallery img') ||
                            e.target.closest('.screenshot-nav') ||
                            e.target.classList.contains('expand-icon')) {
                            return;
                        }
                        const locked = row.classList.contains('locked-expanded');
                        if (locked) {
                            row.classList.remove('locked-expanded');
                            const icon = row.querySelector('.expand-icon');
                            if (icon) {
                                icon.setAttribute('aria-expanded', 'false');
                                icon.title = 'Expand for details';
                            }
                        } else {
                            row.classList.add('locked-expanded');
                            const icon = row.querySelector('.expand-icon');
                            if (icon) {
                                icon.setAttribute('aria-expanded', 'true');
                                icon.title = 'Collapse details';
                            }
                        }
                        scrollRowIfNeeded();
                    });

                    // When locking a row, scroll it into view
                    function scrollRowIfNeeded() {
                        row.scrollIntoView({block: 'nearest', behavior: 'smooth'});
                    }

                    sessionsBody.appendChild(row);
                });

                // Reset sorting
                currentSort = {column: 1, direction: 'asc'};
                Array.from(document.getElementsByTagName('th')).forEach(header => {
                    header.classList.remove('sort-asc', 'sort-desc');
                });

                // Deep link support (moved here so it works after data is loaded)
                const params = new URLSearchParams(window.location.search);
                const sessionId = params.get('session');
                if (sessionId) {
                    showSessionDetails(sessionId);
                } else {
                    document.getElementById('main-table-section').style.display = '';
                    document.getElementById('session-details').classList.remove('active');
                }

                window._trailblazeSessions = data.sessions; // <-- Make sessions globally available
            } catch (error) {
                showError(`Error updating UI: ${error.message}`);
            }
        }

        // Modal functionality for screenshots
        const modal = document.getElementById('screenshot-modal');
        const modalImg = document.getElementById('modal-image');

        function showScreenshot(url) {
            modal.style.display = 'block';
            modalImg.src = url;
        }

        modal.onclick = function () {
            modal.style.display = 'none';
        }

        // SPA navigation handler for details
        function navigateToSession(sessionId) {
            showSessionDetails(sessionId);
            window.location.hash = `session/${sessionId}`;
        }

        // Show session details section
        function showSessionDetails(sessionId) {
            document.body.classList.add('session-details-mode');
            document.getElementById('main-table-section').style.display = 'none';
            const detailsDiv = document.getElementById('session-details');
            detailsDiv.classList.add('active');
            window.scrollTo(0, 0); // Scroll to top of page

            // Find session data
            const session = window._trailblazeSessions?.find(s => s.sessionId === sessionId);
            // Show session ID below header
            document.getElementById('session-id-header').textContent = session.sessionId;

            // Add session-specific header summaries
            let sessionHeaderSummaries = document.querySelector('.session-header-summaries');
            if (!sessionHeaderSummaries) {
                sessionHeaderSummaries = document.createElement('div');
                sessionHeaderSummaries.className = 'session-header-summaries';
                const headerFlex = document.getElementById('header-flex');
                headerFlex.insertBefore(sessionHeaderSummaries, headerFlex.children[1]);
            }

            sessionHeaderSummaries.innerHTML = `
                <div class="session-header-summary">
                    <div class="session-header-summary-title">Session Outcome</div>
                    <div class="session-header-outcomes">
                        <span class="session-header-outcome"><span class="session-header-outcome-emoji">${formatOutcome(session.outcome)}</span> ${session.outcome || '-'}</span>
                    </div>
                </div>
                <div class="header-divider"></div>
                <div class="session-header-summary">
                    <div class="session-header-summary-title">LLM Usage</div>
                    <div class="session-header-summary-value">${formatCost(session.totalCostInUsDollars || 0)}<div class="sub-value">${session.llmCallCount} Call${session.llmCallCount === 1 ? '' : 's'}</div><div class="model-text">${session.llmModelId || '-'}</div></div>
                </div>
                <div class="header-divider"></div>
                <div class="session-header-summary">
                    <div class="session-header-summary-title">Duration</div>
                    <div class="session-header-summary-value">${(() => {
                const totalSeconds = session.sessionDurationSeconds || 0;
                const minutes = Math.floor(totalSeconds / 60);
                const seconds = Math.floor(totalSeconds % 60);
                return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
            })()}</div>
                </div>
            `;
            sessionHeaderSummaries.style.display = 'flex';

            // Build details HTML
            detailsDiv.innerHTML = `
                <button class="back-btn" onclick="backToReport()">← Back to Report</button>
                <h2>Session Details</h2>
                ${(session.eventGroups || []).map((group, idx) => `
                        <div class='detail-row expanded'>
                            <div class='group-header'>
                                <div>
                                    ${group.prompt ?
                    `<pre class="prompt">${group.prompt}</pre>` :
                    ``
                }

                                </div>
                                <span class='expand-icon'>▶</span>
 ${group.kotlin ?
                    `<button class="code-button" data-code="${encodeURIComponent(group.kotlin)}" onclick="event.stopPropagation(); showCode(this.getAttribute('data-code'), 'Kotlin')">Kotlin</button>` :
                    ''}
                    ${group.yaml ?
                    `<button class="code-button" data-code="${encodeURIComponent(group.yaml)}" onclick="event.stopPropagation(); showCode(this.getAttribute('data-code'), 'YAML')">YAML</button>` :
                    ''}
                            </div>
                            <div class='group-content'>
                                <div class='event-groups-row'>` +
                `${Array.isArray(group.events) ? (() => {
                    return group.events.map((ev, evIdx) => {
                        let classShort = '';
                        if (ev.class) {
                            const parts = ev.class.split('.');
                            classShort = parts[parts.length - 1];
                        }
                        let screenshotImg = '';
                        if (ev.screenshotFile != null) {
                            const screenshotId = `screenshot-${idx}-${evIdx}`;
                            if (ev.x >= 0 && ev.y >= 0) {
                                drawScreenshotCircle(screenshotId, ev.deviceWidth, ev.deviceHeight, ev.x, ev.y);
                            }
                            screenshotImg = `<div style="position:relative;"><img id='${screenshotId}' src='${ev.screenshotFile}' alt='screenshot' class='event-group-screenshot' onclick='showScreenshot(\"${ev.screenshotFile}\")'></div>`;
                        }
                        let content = '';
                        if (ev.code !== undefined) {
                            content = `<pre class='event-group-json'>${ev.code}</pre>`;
                        } else {
                            content = `<pre class='event-group-json'>${JSON.stringify(ev, null, 2)}</pre>`;
                        }
                        let backgroundColors = {
                            "MaestroDriver": "lightblue",
                            "MaestroCommand": "lightpink",
                            "TrailblazeTool": "lightyellow",
                            "LlmRequest": "lightgreen",
                            "AgentStatusChanged": "lightgray",
                            "SessionStatusChanged": "lightcyan"
                        };
                        return `<div class='event-group-box'>
                                            <div class='event-group-header' style='background-color: ${backgroundColors[classShort] || ''};'>
                                                ${classShort ? `<span class='event-group-class'>${classShort}</span><span class='event-group-elapsed'>${(() => {
                            const ms = ev.elapsedTimeMs || 0;
                            const totalSeconds = ms / 1000;
                            const minutes = Math.floor(totalSeconds / 60);
                            const seconds = Math.floor(totalSeconds % 60);
                            return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
                        })()}</span>` : ''}
                                            </div>
                                            ${screenshotImg}
                                            <div class='event-group-content'>
                                                ${content}
                                            </div>
                                        </div>`;
                    }).join('');
                })() : (() => {
                    let classShort = '';
                    if (group.class) {
                        const parts = group.class.split('.');
                        classShort = parts[parts.length - 1];
                    }
                    let screenshotImg = '';
                    if (group.screenshotFile) {
                        screenshotImg = `<img id='screenshot${screenshotId}' src='${group.screenshotFile}' alt='screenshot' class='event-group-screenshot' onclick='showScreenshot(\"${group.screenshotFile}\")'></div>`;
                    }
                    let content = '';
                    if (!group.screenshotFile) {
                        if (group.code !== undefined) {
                            content = `<pre class='event-group-json'>${group.code}</pre>`;
                        } else {
                            content = `<pre class='event-group-json'>${JSON.stringify(group, null, 2)}</pre>`;
                        }
                    }
                    return `<div class='event-group-box'>
                                        <div class='event-group-elapsed'>${(() => {
                        const ms = group.elapsedTimeMs || 0;
                        const totalSeconds = ms / 1000;
                        const minutes = Math.floor(totalSeconds / 60);
                        const seconds = Math.floor(totalSeconds % 60);
                        return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
                    })()}</div>
                                        ${classShort ? `<div class='event-group-class'>${classShort}</div>` : ''}
                                        ${screenshotImg}
                                        ${content}
                                    </div>`;
                })()}
                            </div>
                        </div>
                    </div>
                    `).join('')}
            `;

            // After the details are shown, replace Kotlin code pre elements with buttons
            document.querySelectorAll('.detail-row pre').forEach(pre => {
                if (pre.textContent.includes('kotlin')) {
                    const button = document.createElement('button');
                    button.className = 'kotlin-button';
                    button.textContent = 'Kotlin';
                    button.onclick = (e) => {
                        e.stopPropagation();
                        showKotlinCode(pre.textContent);
                    };
                    pre.parentNode.replaceChild(button, pre);
                }
            });
        }

        // Back to main report
        function backToReport() {
            document.getElementById('session-id-header').textContent = '';
            document.body.classList.remove('session-details-mode');
            let sessionHeaderSummaries = document.querySelector('.session-header-summaries');
            if (sessionHeaderSummaries) sessionHeaderSummaries.style.display = 'none';
            window.location.hash = '';
            document.getElementById('main-table-section').style.display = '';
            const detailsDiv = document.getElementById('session-details');
            detailsDiv.classList.remove('active');
            detailsDiv.innerHTML = ''; // Clear the content
        }

        // Listen for browser navigation
        window.addEventListener('hashchange', () => {
            const hash = window.location.hash;
            if (hash.startsWith('#session/')) {
                const sessionId = hash.substring(9); // Remove '#session/' prefix
                showSessionDetails(sessionId);
            } else {
                backToReport();
            }
        });

        // Add this after the DOMContentLoaded event listener
        document.addEventListener('DOMContentLoaded', function () {
            // Deep link support
            const hash = window.location.hash;
            if (hash.startsWith('#session/')) {
                const sessionId = hash.substring(9); // Remove '#session/' prefix
                showSessionDetails(sessionId);
            }
        });

        function dataLoaded(data) {
            window._trailblazeSessions = data.sessions;
            updateUI(data);

            // Check for hash-based routing after data is loaded
            const hash = window.location.hash;
            if (hash.startsWith('#session/')) {
                const sessionId = hash.substring(9);
                showSessionDetails(sessionId);
            } else {
                document.getElementById('main-table-section').style.display = '';
                document.getElementById('session-details').classList.remove('active');
                // Trigger initial sort by Start Time
                sortTable(1);
            }
        }

        // Initialize with pre-fetched data
        function initializeApp() {
            if (window._trailblazeSessions) {
                console.log(`Using pre-fetched sessions: ${window._trailblazeSessions}`);
                dataLoaded(window._trailblazeSessions);
            } else {
                // If data isn't available yet, wait for it
                const checkData = setInterval(() => {
                    if (window._trailblazeSessions) {
                        // Parse the data if it's a string
                        const sessions = typeof window._trailblazeSessions === 'string'
                            ? JSON.parse(window._trailblazeSessions)
                            : window._trailblazeSessions;

                        console.log(`Using pre-fetched sessions: ${sessions.length}`);
                        dataLoaded({sessions: sessions});
                        clearInterval(checkData);
                    }
                }, 100);
            }
        }

        // Start initialization when DOM is ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', initializeApp);
        } else {
            initializeApp();
        }

        function toggleGroup(element) {
            // Don't toggle if clicking on a screenshot or its controls
            if (event.target.closest('.screenshot-gallery') ||
                event.target.closest('.screenshot-nav') ||
                event.target.closest('.screenshot-controls')) {
                return;
            }

            element.classList.toggle('expanded');

            // If expanding, scroll the element into view
            if (element.classList.contains('expanded')) {
                element.scrollIntoView({behavior: 'smooth', block: 'nearest'});
            }
        }

    </script>
</#noparse>
