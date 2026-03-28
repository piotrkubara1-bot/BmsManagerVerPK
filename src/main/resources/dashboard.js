const API_BASE = window.BMS_API_BASE || "http://127.0.0.1:8090";
let cachedSettingsRows = [];

function initTabs() {
	const tabs = Array.from(document.querySelectorAll(".tab"));
	tabs.forEach((tab) => {
		tab.addEventListener("click", () => {
			tabs.forEach((t) => t.classList.remove("active"));
			tab.classList.add("active");

			const tabName = tab.getAttribute("data-tab");
			Array.from(document.querySelectorAll(".tab-content")).forEach((section) => {
				section.classList.toggle("active", section.id === `tab-${tabName}`);
			});
		});
	});
}

async function refreshHealth() {
	const el = document.getElementById("healthStatus");
	try {
		const response = await fetch(`${API_BASE}/api/health`);
		const json = await response.json();
		const dbState = json.dbConnected ? "DB OK" : "DB OFF";
		const modules = (json.modulesSeen || []).join(", ") || "none";
		const allowed = Array.isArray(json.allowedModules) && json.allowedModules.length
			? json.allowedModules.join(",")
			: "all";
		el.textContent = `Service: ${json.status.toUpperCase()} | ${dbState} | Modules: ${modules} | Allowed: ${allowed}`;
	} catch (err) {
		el.textContent = "Service unreachable";
	}
}

function renderLive(modules) {
	const grid = document.getElementById("moduleGrid");
	if (!Array.isArray(modules) || modules.length === 0) {
		grid.innerHTML = "<div class='module-card'>No telemetry yet.</div>";
		return;
	}

	grid.innerHTML = modules.map((item) => {
		const cells = Array.isArray(item.cellsMv) ? item.cellsMv : [];
		return `
			<div class="module-card">
				<h3>Module ${item.moduleId}</h3>
				<div class="metric"><span>Voltage</span><strong>${Number(item.voltageV).toFixed(3)} V</strong></div>
				<div class="metric"><span>Current</span><strong>${Number(item.currentA).toFixed(3)} A</strong></div>
				<div class="metric"><span>SOC</span><strong>${Number(item.socPercent).toFixed(2)} %</strong></div>
				<div class="metric"><span>Status</span><strong>${item.statusCode}</strong></div>
				<div class="metric"><span>Cells</span><strong>${cells.length ? cells.join(" /") : "-"}</strong></div>
			</div>
		`;
	}).join("");
}

function renderEvents(events) {
	const tbody = document.getElementById("eventsTableBody");
	if (!Array.isArray(events) || events.length === 0) {
		tbody.innerHTML = "<tr><td colspan='5'>No events.</td></tr>";
		return;
	}

	tbody.innerHTML = events.map((event) => `
		<tr>
			<td>${event.timestamp || "-"}</td>
			<td>${event.moduleId || "-"}</td>
			<td>${event.severity || "-"}</td>
			<td>${event.eventCode || "-"}</td>
			<td>${event.message || "-"}</td>
		</tr>
	`).join("");
}

function renderStats(stats) {
	const container = document.getElementById("statsCards");
	if (!Array.isArray(stats) || stats.length === 0) {
		container.innerHTML = "<div class='stats-card'>No statistics available yet.</div>";
		return;
	}

	container.innerHTML = stats.map((item) => `
			<div class="stats-card">
				<h3>Module ${item.moduleId}</h3>
				<div class="metric"><span>Avg SOC</span><strong class="value">${Number(item.avgSoc).toFixed(2)} %</strong></div>
				<div class="metric"><span>SOC Range</span><strong>${Number(item.minSoc).toFixed(2)} - ${Number(item.maxSoc).toFixed(2)} %</strong></div>
				<div class="metric"><span>Voltage Range</span><strong>${Number(item.minVoltage).toFixed(3)} - ${Number(item.maxVoltage).toFixed(3)} V</strong></div>
				<div class="metric"><span>Current Range</span><strong>${Number(item.minCurrent).toFixed(3)} - ${Number(item.maxCurrent).toFixed(3)} A</strong></div>
				<div class="metric"><span>Samples</span><strong>${item.sampleCount}</strong></div>
				<div class="metric"><span>Last Status</span><strong>${item.lastStatusCode}</strong></div>
			</div>
		`).join("");
}

function renderSettings(modules) {
	const tableBody = document.getElementById("settingsTableBody");
	const keySelect = document.getElementById("settingsKey");

	if (!Array.isArray(modules) || modules.length === 0) {
		tableBody.innerHTML = "<tr><td colspan='6'>No settings available.</td></tr>";
		keySelect.innerHTML = "";
		cachedSettingsRows = [];
		return;
	}

	const modulesById = new Map();
	modules.forEach((module) => {
		modulesById.set(Number(module.moduleId), module.settings || []);
	});

	const rowByKey = new Map();
	modules.forEach((module) => {
		(module.settings || []).forEach((setting) => {
			if (!rowByKey.has(setting.key)) {
				rowByKey.set(setting.key, {
					key: setting.key,
					label: setting.label,
					unit: setting.unit,
					min: setting.min,
					max: setting.max,
					writable: !!setting.writable,
					values: {}
				});
			}
			rowByKey.get(setting.key).values[module.moduleId] = setting.value;
		});
	});

	cachedSettingsRows = Array.from(rowByKey.values());

	tableBody.innerHTML = cachedSettingsRows.map((row) => {
		const rw = row.writable ? "" : " (RO)";
		return `
			<tr>
				<td>${row.label}${rw}</td>
				<td>${row.unit}</td>
				<td>${formatSettingValue(row.values[1])}</td>
				<td>${formatSettingValue(row.values[2])}</td>
				<td>${formatSettingValue(row.values[3])}</td>
				<td>${formatSettingValue(row.values[4])}</td>
			</tr>
		`;
	}).join("");

	keySelect.innerHTML = cachedSettingsRows
		.filter((row) => row.writable)
		.map((row) => `<option value="${row.key}">${row.label} (${row.unit})</option>`)
		.join("");
}

function formatSettingValue(value) {
	if (typeof value === "undefined") {
		return "-";
	}
	return Number(value).toFixed(3);
}

function wireSettingsForm() {
	const form = document.getElementById("settingsForm");
	if (!form) {
		return;
	}

	form.addEventListener("submit", async (event) => {
		event.preventDefault();
		const moduleId = document.getElementById("settingsModule").value;
		const key = document.getElementById("settingsKey").value;
		const value = document.getElementById("settingsValue").value;
		const statusEl = document.getElementById("settingsStatus");

		if (!key) {
			statusEl.textContent = "No writable setting available.";
			return;
		}

		try {
			const body = new URLSearchParams({ moduleId, key, value }).toString();
			const response = await fetch(`${API_BASE}/api/cell-settings`, {
				method: "POST",
				headers: { "Content-Type": "application/x-www-form-urlencoded" },
				body
			});
			if (!response.ok) {
				const errJson = await response.json();
				statusEl.textContent = `Update failed: ${errJson.error || response.status}`;
				statusEl.style.color = "#ff6b6b";
				return;
			}
			statusEl.textContent = "Setting updated.";
			statusEl.style.color = "#29d391";
			await refreshSettings();
		} catch (error) {
			statusEl.textContent = "Update failed: service unreachable";
			statusEl.style.color = "#ff6b6b";
		}
	});
}

async function refreshSettings() {
	try {
		const response = await fetch(`${API_BASE}/api/cell-settings`);
		const settings = await response.json();
		renderSettings(settings);
	} catch (error) {
		renderSettings([]);
	}
}

async function refreshData() {
	try {
		const latestResponse = await fetch(`${API_BASE}/api/latest`);
		const latest = await latestResponse.json();
		renderLive(latest);
	} catch (err) {
		renderLive([]);
	}

	try {
		const eventsResponse = await fetch(`${API_BASE}/api/events?limit=40`);
		const events = await eventsResponse.json();
		renderEvents(events);
	} catch (err) {
		renderEvents([]);
	}

	try {
		const statsResponse = await fetch(`${API_BASE}/api/statistics`);
		const stats = await statsResponse.json();
		renderStats(stats);
	} catch (err) {
		renderStats([]);
	}

	await refreshSettings();
}

initTabs();
wireSettingsForm();
refreshHealth();
refreshData();
setInterval(refreshHealth, 3000);
setInterval(refreshData, 1000);
