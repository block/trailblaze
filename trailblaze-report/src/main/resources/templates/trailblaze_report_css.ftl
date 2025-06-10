<#noparse>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            line-height: 1.6;
            margin: 0;
            padding: 10px;
            background: #f5f5f5;
        }

        .container {
            max-width: 100%;
            margin: 0 auto;
            background: white;
            padding: 8px 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
            overflow-x: hidden;
        }

        h1 {
            color: #333;
            margin: 0 0 18px 0;
            font-size: 2em;
            text-align: left;
        }

        .summary-row {
            display: flex;
            justify-content: center;
            gap: 24px;
            margin-bottom: 32px;
        }

        .summary-stats {
            display: flex;
            gap: 24px;
        }

        .stat-card {
            background: #f8f9fa;
            padding: 10px 18px;
            border-radius: 8px;
            text-align: center;
            min-width: 120px;
            box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
        }

        .stat-card h3 {
            margin: 0;
            color: #666;
            font-size: 0.9em;
        }

        .stat-card .value {
            font-size: 1.8em;
            font-weight: bold;
            color: #2c3e50;
        }

        .stat-card .sub-value {
            font-size: 0.6em;
            color: #666;
            margin-top: 2px;
        }

        #outcome-summary {
            font-size: 0.8em;
            line-height: 1.4;
            display: grid;
            grid-template-columns: auto 1fr;
            gap: 8px 4px;
            align-items: center;
        }

        .sessions-table {
            width: 100%;
            min-width: 800px;
            border-collapse: collapse;
            margin-top: 20px;
            table-layout: auto;
        }

        .sessions-table th,
        .sessions-table td {
            padding: 12px;
            text-align: left;
            border-bottom: 1px solid #ddd;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .sessions-table th:nth-child(1) {
            width: 80px;
            min-width: 80px;
        }

        /* Outcome */
        .sessions-table th:nth-child(2) {
            width: 160px;
            min-width: 160px;
        }

        /* Session */
        .sessions-table th:nth-child(3) {
            width: 120px;
            min-width: 120px;
        }

        /* Duration */
        .sessions-table th:nth-child(4) {
            width: 100px;
            min-width: 100px;
        }

        /* Cost */
        .sessions-table th:nth-child(5) {
            width: 140px;
            min-width: 140px;
        }

        /* Tasks */
        .sessions-table th:nth-child(6) {
            width: auto;
            min-width: 200px;
        }

        /* Screenshots */
        .sessions-table th:nth-child(7),
        .sessions-table td:nth-child(7) {
            width: 120px;
            min-width: 120px;
            text-align: center;
        }

        /* Details */
        .sessions-table th:nth-child(8),
        .sessions-table td:nth-child(8) {
            width: 80px;
            min-width: 80px;
            text-align: center;
        }

        .details-btn {
            background: #f3f6fa;
            border: 1px solid #d1d5db;
            border-radius: 4px;
            padding: 4px 10px;
            font-size: 0.95em;
            color: #2c3e50;
            cursor: pointer;
            transition: background 0.15s, border 0.15s;
        }

        .details-btn:hover {
            background: #e9ecef;
            border-color: #bfc5cc;
        }

        .sessions-table th {
            background-color: #f8f9fa;
            font-weight: 600;
            cursor: pointer;
            user-select: none;
            position: relative;
            padding-right: 20px;
        }

        .sessions-table th:hover {
            background-color: #e9ecef;
        }

        .sessions-table th::after {
            content: '↕';
            position: absolute;
            right: 5px;
            color: #999;
        }

        .sessions-table th.sort-asc::after {
            content: '↑';
            color: #2c3e50;
        }

        .sessions-table th.sort-desc::after {
            content: '↓';
            color: #2c3e50;
        }

        .sessions-table tr {
            transition: background 0.15s;
            cursor: pointer;
        }

        .sessions-table tr:hover {
            background-color: #f3f6fa;
        }

        .sessions-table tr td .screenshot-gallery img {
            cursor: pointer;
        }

        .status-success {
            color: #28a745;
        }

        .status-failure {
            color: #dc3545;
        }

        .status-in-progress {
            color: #ffc107;
        }

        .sessions-table td:last-child {
            text-align: center;
        }

        .screenshot-gallery {
            position: relative;
            min-width: 120px;
            min-height: 80px;
            max-width: 180px;
            max-height: 120px;
            width: fit-content;
            height: 100%;
            display: flex;
            flex-direction: column;
            align-items: center;
            background: #f8f9fa;
            border-radius: 4px;
            overflow: hidden;
            margin: 0 auto;
            transition: max-width 0.2s, max-height 0.2s, min-width 0.2s, min-height 0.2s;
        }

        .screenshot-gallery img {
            width: 100%;
            height: calc(100% - 40px);
            min-height: 60px;
            max-height: 100px;
            object-fit: contain;
            border-radius: 4px 4px 0 0;
            cursor: pointer;
            transition: max-height 0.2s, min-height 0.2s;
        }

        tr.expanded .screenshot-gallery,
        tr.locked-expanded .screenshot-gallery {
            min-width: 300px;
            min-height: 400px;
            max-width: 500px;
            max-height: 500px;
        }

        tr.expanded .screenshot-gallery img,
        tr.locked-expanded .screenshot-gallery img {
            min-height: 360px;
            max-height: 460px;
        }

        tr.expanded .screenshot-controls,
        tr.locked-expanded .screenshot-controls {
            display: flex !important;
        }

        .screenshot-controls {
            display: none;
            align-items: center;
            justify-content: center;
            width: 100%;
            padding: 4px;
            background: #f8f9fa;
        }

        .screenshot-nav {
            position: relative;
            background: rgba(0, 0, 0, 0.5);
            color: white;
            border: none;
            padding: 4px 8px;
            cursor: pointer;
            border-radius: 4px;
            font-size: 16px;
            margin: 0 4px;
        }

        .screenshot-nav:hover {
            background: rgba(0, 0, 0, 0.7);
        }

        .screenshot-counter {
            position: relative;
            background: rgba(0, 0, 0, 0.5);
            color: white;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 12px;
            margin: 0 4px;
        }

        .modal {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.8);
            z-index: 1000;
        }

        .modal-content {
            max-width: 90%;
            max-height: 90%;
            margin: auto;
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
        }

        .modal-content img {
            max-width: 100%;
            max-height: 90vh;
        }

        .error-message {
            color: #dc3545;
            padding: 20px;
            text-align: center;
            background: #fff;
            border-radius: 8px;
            margin: 20px 0;
        }

        .sessions-table td {
            padding: 12px;
            text-align: left;
            border-bottom: 1px solid #ddd;
            vertical-align: top;
        }

        .status-message {
            font-size: 0.85em;
            line-height: 1.4;
            color: #666;
            max-width: 400px;
            white-space: normal;
            word-wrap: break-word;
        }

        .prompt-list {
            margin-top: 0;
            padding-top: 0;
        }

        .prompt-header {
            font-weight: 500;
            color: #666;
            margin-bottom: 4px;
        }

        .prompt-item {
            margin-bottom: 4px;
            padding-left: 12px;
            position: relative;
        }

        .prompt-item::before {
            content: "•";
            position: absolute;
            left: 0;
            color: #999;
        }

        .model-text {
            font-size: 0.7em;
            color: #666;
            display: block;
            margin-top: 2px;
        }

        .session-id {
            font-size: 0.8em;
            font-family: monospace;
            color: #666;
            margin-left: 8px;
            word-break: break-all;
            white-space: normal;
        }

        .outcome-emoji {
            font-size: 1em;
            vertical-align: middle;
            white-space: nowrap;
        }

        .sessions-table td:first-child {
            white-space: normal;
            word-break: break-word;
            min-width: 200px;
        }

        .llm-calls-text {
            font-size: 0.8em;
            color: #666;
            display: block;
            margin-top: 2px;
        }

        .tasks-text {
            font-size: 0.8em;
            color: #666;
            display: block;
            margin-top: 2px;
        }

        .header-flex {
            display: flex;
            align-items: flex-end;
            justify-content: space-between;
            margin-bottom: 28px;
            gap: 24px;
            position: relative;
            flex-wrap: wrap;
            padding-top: 18px;
            padding-bottom: 8px;
            min-width: 0;
        }

        .header-title {
            font-size: 2em;
            font-weight: bold;
            color: #333;
            margin: 0 0 2px 0;
            line-height: 1.1;
        }

        .session-id-header {
            font-size: 0.97em;
            color: #888;
            margin: 0 0 2px 0;
            font-family: monospace;
            word-break: break-all;
            white-space: normal;
            display: none;
        }

        body.session-details-mode .session-id-header {
            display: block;
        }

        .header-summaries {
            display: flex;
            align-items: flex-start;
            gap: 32px;
        }

        .header-summary {
            font-size: 0.95em;
            color: #222;
            display: flex;
            flex-direction: column;
            align-items: flex-end;
        }

        .header-summary-title {
            font-size: 0.95em;
            color: #666;
            font-weight: 600;
            margin-bottom: 2px;
        }

        .header-summary-value {
            font-size: 1.1em;
            font-weight: 500;
            color: #222;
            line-height: 1.1;
        }

        .header-summary-value .sub-value {
            font-size: 0.8em;
            color: #666;
            margin-top: 2px;
            display: block;
        }

        .header-divider {
            width: 1px;
            background: #e0e0e0;
            margin: 0 18px;
            align-self: stretch;
        }

        .header-outcomes {
            display: flex;
            gap: 16px;
            font-size: 0.95em;
            align-items: center;
        }

        .header-outcome {
            display: flex;
            align-items: center;
            gap: 4px;
            color: #222;
            font-size: 1em;
        }

        .header-outcome-emoji {
            font-size: 1.1em;
        }

        .expand-icon {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 22px;
            height: 22px;
            font-size: 1.1em;
            margin-right: 6px;
            cursor: pointer;
            transition: transform 0.2s;
            color: #888;
            user-select: none;
        }

        .expand-icon[aria-expanded="true"] {
            transform: rotate(90deg);
            color: #2c3e50;
        }

        .session-details {
            width: 100%;
            max-width: 100%;
            box-sizing: border-box;
            display: none;
        < #-- background: #f8f9fa;
        --> border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
        }

        .session-details.active {
            display: block;
            margin-left: auto;
            margin-right: auto;
            padding-left: 0;
            padding-right: 0;
        }

        body.session-details-mode .container {
            max-width: 100vw;
        }

        body.session-details-mode #header-summaries {
            display: none;
        }

        body.session-details-mode #main-table-section {
            display: none !important;
        }

        body.session-details-mode .session-header-summaries {
            display: flex !important;
        }

        .session-header-summaries {
            display: none;
            align-items: flex-start;
            gap: 48px;
            flex: 1 1 300px;
            margin-left: 0;
            margin-top: 0;
            position: static;
            min-width: 0;
            flex-shrink: 1;
            flex-wrap: wrap;
        }

        @media (max-width: 1100px) {
            .session-header-summaries {
                gap: 18px;
            }

            .session-header-summary-value {
                font-size: 1em;
            }
        }

        .session-header-summary {
            font-size: 1em;
            color: #222;
            display: flex;
            flex-direction: column;
            align-items: flex-start;
            min-width: 120px;
        }

        .session-header-summary-title {
            font-size: 1em;
            color: #666;
            font-weight: 600;
            margin-bottom: 2px;
        }

        .session-header-summary-value {
            font-size: 1.2em;
            font-weight: 600;
            color: #222;
            line-height: 1.1;
        }

        .session-header-outcomes {
            display: flex;
            gap: 12px;
            font-size: 1em;
            align-items: center;
        }

        .session-header-outcome {
            display: flex;
            align-items: center;
            gap: 4px;
            color: #222;
            font-size: 1em;
            font-weight: 500;
        }

        .session-header-outcome-emoji {
            font-size: 1.2em;
        }

        .back-btn {
            background: #fff;
            border: 1px solid #d1d5db;
            border-radius: 4px;
            padding: 6px 16px;
            font-size: 1em;
            color: #2c3e50;
            cursor: pointer;
            margin-bottom: 18px;
            transition: background 0.15s, border 0.15s;
        }

        .back-btn:hover {
            background: #f3f6fa;
            border-color: #bfc5cc;
        }

        .session-details h2 {
            margin-top: 0;
        }

        .session-details .detail-label {
            font-weight: 600;
            color: #555;
            margin-right: 8px;
        }

        .session-details .detail-row {
            margin-bottom: 48px;
        }


        .session-details  .detail-row pre {
            background: #fff;
            padding: 12px;
            border-radius: 4px;
            margin: 8px 0 16px 0;
            font-family: monospace;
            word-break: break-word;
        }

        .session-details .detail-row .prompt {
            border: 1px solid #e0e0e0;
        }

        .session-details .detail-value {
            font-family: monospace;
            color: #333;
        }

        .session-details .screenshot-gallery {
            margin: 18px 0 0 0;
        }

        .event-groups-row {
            display: grid;
            gap: 16px;
            margin-top: 8px;
            align-items: start;
            padding-left: 0;
            max-width: 100%;
            box-sizing: border-box;
            overflow-x: auto;
        }

        /*sm	@media (min-width: 576px)	≥ 576px*/
        /*md	@media (min-width: 768px)	≥ 768px*/
        /*lg	@media (min-width: 992px)	≥ 992px*/
        /*xl	@media (min-width: 1200px)	≥ 1200px*/
        /*xxl	@media (min-width: 1400px)	≥ 1400px*/

        @media (min-width: 576px) {
            .event-groups-row {
                grid-template-columns: repeat(2, minmax(0px, 1fr));
            }
        }


        @media (min-width: 768px) {
            .event-groups-row {
                grid-template-columns: repeat(3, minmax(0px, 1fr));
            }
        }


        @media (min-width: 992px) {
            .event-groups-row {
                grid-template-columns: repeat(4, minmax(0px, 1fr));
            }
        }


        @media (min-width: 1200px) {
            .event-groups-row {
                grid-template-columns: repeat(5, minmax(0px, 1fr));
            }
        }

        @media (min-width: 1600px) {
            .event-groups-row {
                grid-template-columns: repeat(6, minmax(0px, 1fr));
            }
        }

        .event-group-box {
            max-height: 600px;
            overflow-y: auto;
            height: auto;
            min-width: 0;
            background: #fff;
            color: #333;
            border: 1px solid #e0e0e0;
            border-radius: 6px;
            font-size: 0.92em;
            font-family: monospace;
            word-break: break-all;
            overflow-x: auto;
            box-shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
            align-items: stretch;
            max-width: 100%;
            box-sizing: border-box;
        }

        .event-group-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 8px;
        }

        .event-group-header .event-group-class {
            font-weight: bold;
            font-size: 0.92em;
            color: #2c3e50;
            word-break: break-word;
        }

        .event-group-header .event-group-elapsed {
            font-size: 0.92em;
            color: #2c3e50;
        }

        .event-group-content *:not(img) {
            padding: 8px;
        }

        .event-group-class {
            font-weight: bold;
            font-size: 0.92em;
            color: #2c3e50;
            margin-bottom: 2px;
            margin-right: 8px;
            word-break: break-all;
        }

        .event-group-json {
            margin-top: 2px;
            font-family: monospace;
            font-size: 0.92em;
            /*white-space: pre-wrap;*/
            overflow-x: auto;
            word-break: break-all;
            line-height: 1.3;
        }

        .event-group-screenshot {
            width: 100%;
            height: 100%;
            object-fit: fill;
            cursor: pointer;
            display: block;
        }

        .event-group-screenshot:hover {
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
        }

        .event-group-elapsed {
            font-size: 0.93em;
            color: #888;
            margin-bottom: 2px;
            font-family: monospace;
        }

        /* Simple checkbox styles */
        .view-toggle {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-left: 16px;
        }

        .view-toggle label {
            font-size: 0.9em;
            color: #666;
            cursor: pointer;
        }

        /* Add styles for the header controls */
        .header-controls {
            display: flex;
            align-items: center;
            gap: 16px;
            margin-left: auto;
        }

        .detail-row {
            margin-bottom: 32px;
            background: #f3f6fa;
            border: 1px solid #e0e0e0;
            border-radius: 8px;
            padding: 16px;
            box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
            cursor: pointer;
            transition: all 0.2s ease;
            margin-left: 0;
        }

        .detail-row:hover {
            border: 1px solid #111111;
        }

        .detail-row .group-header {
            /*display: flex;*/
            /*align-items: center;*/
            /*justify-content: space-between;*/
            margin-bottom: 8px;
            /*padding-left: 0;*/
        }

        .detail-row .group-header .expand-icon {
            font-size: 1.2em;
            color: #666;
            transition: transform 0.2s ease;
        }

        .detail-row.expanded .group-header .expand-icon {
            transform: rotate(90deg);
        }

        .detail-row .group-content {
            max-height: 100px;
            overflow: hidden;
            transition: max-height 0.3s ease;
            position: relative;
            padding-left: 0;
        }

        .detail-row.expanded .group-content {
            max-height: none;
        }

        .detail-row .group-content::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            height: 40px;
            background: linear-gradient(transparent, #f8f9fa);
            pointer-events: none;
            opacity: 1;
            transition: opacity 0.2s ease;
        }

        .detail-row.expanded .group-content::after {
            opacity: 0;
        }

        .detail-label {
            font-weight: 600;
            color: #555;
            margin-right: 8px;
            display: block;
            margin-bottom: 8px;
        }

        /* Ensure the container of groups is a vertical list and not indented */
        #session-details {
            display: flex;
            flex-direction: column;
            gap: 0;
            align-items: stretch;
        }

        /* Code modal styles */
        .code-modal {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.8);
            z-index: 1000;
        }

        .code-modal-content {
            position: relative;
            background: #fff;
            margin: 5% auto;
            padding: 20px;
            width: 80%;
            max-width: 800px;
            max-height: 80vh;
            border-radius: 8px;
            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
        }

        .code-modal pre {
            background: #f8f9fa;
            padding: 16px;
            border-radius: 4px;
            overflow-x: auto;
            max-height: calc(80vh - 100px);
            margin: 0;
            font-family: monospace;
            white-space: pre-wrap;
            word-wrap: break-word;
        }

        .code-modal-close {
            position: absolute;
            right: 20px;
            top: 20px;
            font-size: 24px;
            cursor: pointer;
            color: #666;
        }

        .code-modal-close:hover {
            color: #333;
        }

        .code-button {
            background: #f3f6fa;
            border: 1px solid #d1d5db;
            border-radius: 4px;
            padding: 4px 12px;
            font-size: 0.9em;
            color: #2c3e50;
            cursor: pointer;
            transition: background 0.15s, border 0.15s;
            margin: 8px 0;
        }

        .code-button:hover {
            background: #e9ecef;
            border-color: #bfc5cc;
        }

        .copy-button {
            background: #2c3e50;
            color: white;
            border: none;
            border-radius: 4px;
            padding: 8px 16px;
            font-size: 0.9em;
            cursor: pointer;
            transition: background 0.15s;
            margin-top: 16px;
        }

        .copy-button:hover {
            background: #1a252f;
        }
    </style>
</#noparse>