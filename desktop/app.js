(function () {
  'use strict';

  const CHECK_ITEMS = [
    { category: '消防安全', standard: '车棚内消防设备设施是否齐全、有效' },
    { category: '排水防汛', standard: '车棚内排水沟、排水口、雨水篦子是否畅通' },
    { category: '物资保障', standard: '车棚内照明及其他设施是否配齐、完好可用' },
    { category: '人员值守', standard: '值守人员是否到岗，职责是否明确，联系电话及通信设备是否畅通' },
    { category: '应急预案', standard: '防汛预案和应急处置措施是否落实，现场人员是否熟悉处置及上报流程' },
    { category: '设施安全', standard: '消防设施、用电线路及棚体结构是否安全完好，无松动、漏电等隐患' },
    { category: '停车秩序', standard: '车辆是否分区有序停放，不占压雨篦子，不堵塞出入口和疏散通道' },
    { category: '通道巡查', standard: '出入口、疏散通道是否畅通，是否按要求巡查并如实留存记录' }
  ];
  const MIN_TEMPLATE_ITEMS = 1;
  const MAX_TEMPLATE_ITEMS = 12;

  const DB_NAME = 'car_shed_inspection_local_v2';
  const STORE_NAME = 'records';
  const SYNC_SCHEMA = 'car-shed-inspection-sync';
  const INSPECTION_TYPES_KEY = 'safety_ledger_inspection_types_v1';
  const DEFAULT_INSPECTION_TYPES = [
    { id: 'car-shed', name: '车棚检查记录', items: CHECK_ITEMS.map(function (item) { return { category: item.category, standard: item.standard }; }) },
    { id: 'shared-bike', name: '共享单车检查记录', items: CHECK_ITEMS.map(function (item) { return { category: item.category, standard: item.standard }; }) }
  ];
  const APP_VERSION = '3.1.0';
  const nowDate = new Date();
  const state = {
    records: [],
    inspectionTypes: [],
    inspectionTypeDraft: [],
    inspectionTemplateTypeId: '',
    formRecord: null,
    editingId: null,
    selectionMode: false,
    selectedIds: new Set(),
    calendarYear: nowDate.getFullYear(),
    calendarMonth: nowDate.getMonth(),
    calendarSelectedDate: todayValue(),
    recordFilter: {
      mode: 'all',
      day: todayValue(),
      year: nowDate.getFullYear(),
      month: nowDate.getMonth() + 1,
      quarter: Math.floor(nowDate.getMonth() / 3) + 1,
      typeId: 'all'
    },
    signatureTarget: null,
    signatureHasInk: false,
    signatureOpen: false,
    toastTimer: null,
    confirmResolve: null,
    fontBytes: null,
    cloudService: null,
    cloudFailureMessage: '',
    cloudLastFailureNotificationAt: 0,
    cloudLastFailureNotificationMessage: '',
    cloudSyncRunning: false,
    cloudArchiveRunning: false,
    cloudAutoTimer: null,
    cloudRecycleTargetId: '',
    onlineHolidays: {},
    holidayRefreshYears: new Set()
  };

  const el = {};
  let dbPromise;
  let signatureDrawing = false;
  let signaturePointerId = null;
  let signatureLastPoint = null;
  let signatureLastMidPoint = null;
  let signatureResizeTimer = null;

  document.addEventListener('DOMContentLoaded', init);

  async function init() {
    cacheElements();
    bindEvents();
    state.cloudService = window.SafetyLedgerCloud ? window.SafetyLedgerCloud.create() : null;
    if (state.cloudService && state.cloudService.isConfigured()) {
      state.cloudFailureMessage = state.cloudService.getSummary().lastError || '';
    }
    state.onlineHolidays = state.cloudService ? state.cloudService.getCachedHolidays() : {};
    state.inspectionTypes = loadInspectionTypes();
    el.inspectionDate.value = todayValue();
    el.calendarSearchDate.value = todayValue();
    try {
      state.records = (await dbGetAll()).map(function (record) {
        normalizeRecord(record);
        return record;
      });
    } catch (error) {
      console.error(error);
      showToast('本地记录读取失败，请重新打开应用');
    }
    renderHome();
    renderCloudUi();
    if (state.cloudService && state.cloudService.isConfigured()) {
      setTimeout(function () { runCloudSync(false); }, 650);
      setTimeout(function () { refreshHolidayYears([nowDate.getFullYear(), state.calendarYear], false); }, 1200);
      scheduleCloudAutoSync();
    }
  }

  function cacheElements() {
    const ids = [
      'home-view', 'form-view', 'new-record-btn', 'empty-new-btn', 'inspection-type-settings-btn',
      'selection-mode-btn', 'selection-actions-panel', 'selection-close-btn', 'selection-hint',
      'selection-scope-hint', 'select-filtered-btn', 'clear-selection-btn', 'export-selected-btn',
      'record-range-select', 'record-type-filter-select', 'record-filter-controls', 'record-filter-day-wrap', 'record-filter-day',
      'record-filter-year-wrap', 'record-filter-year', 'record-filter-month-wrap', 'record-filter-month',
      'record-filter-quarter-wrap', 'record-filter-quarter', 'record-list', 'empty-state',
      'calendar-focus-title', 'calendar-focus-lunar', 'calendar-today-btn', 'calendar-month-title',
      'calendar-prev-btn', 'calendar-next-btn', 'calendar-search-date', 'calendar-search-btn',
      'calendar-grid', 'filtered-empty-state', 'filtered-empty-title',
      'list-sort-label', 'sync-export-btn', 'sync-import-btn', 'sync-file-input',
      'cloud-status-pill', 'cloud-sync-description', 'cloud-unit-summary', 'cloud-unit-name',
      'cloud-provider-summary', 'cloud-last-sync', 'cloud-setup-btn', 'cloud-sync-now-btn',
      'cloud-devices-btn', 'cloud-recycle-btn', 'holiday-update-btn',
      'cloud-failure-banner', 'cloud-failure-message', 'cloud-failure-retry-btn', 'cloud-failure-close-btn',
      'form-back-btn', 'form-title', 'save-top-btn', 'inspection-form', 'inspection-sheet-title',
      'inspection-type', 'inspection-date', 'inspection-location', 'inspection-items', 'items-progress',
      'inspection-photo-input', 'inspection-photo-btn', 'inspection-photo-grid',
      'inspection-photo-count', 'rectification-section', 'rectification-opinion',
      'rectification-photo-input', 'rectification-photo-btn', 'rectification-photo-grid',
      'rectification-completed', 'rectification-status-pill', 'signature-step-number',
      'signature-progress', 'delete-record-btn', 'save-record-btn', 'signature-modal',
      'signature-modal-title', 'signature-canvas', 'signature-cancel-btn', 'signature-save-btn',
      'signature-clear-btn', 'progress-overlay', 'progress-title', 'progress-message',
      'progress-fill', 'confirm-dialog', 'confirm-title', 'confirm-message',
      'confirm-cancel-btn', 'confirm-ok-btn', 'export-choice-dialog', 'export-choice-message',
      'export-combined-btn', 'export-individual-btn', 'export-choice-cancel-btn',
      'inspection-type-dialog', 'inspection-type-list', 'inspection-type-add-btn',
      'inspection-type-save-btn', 'inspection-type-close-btn', 'template-type-select',
      'inspection-template-list', 'inspection-template-add-btn',
      'cloud-setup-dialog', 'cloud-setup-title', 'cloud-setup-close-btn',
      'cloud-provider-select', 'cloud-provider-help', 'cloud-endpoint-label', 'cloud-endpoint-help',
      'cloud-endpoint', 'cloud-team-name', 'cloud-password', 'cloud-test-btn', 'cloud-test-result',
      'cloud-device-name', 'cloud-existing-summary', 'cloud-setup-submit-btn', 'cloud-disconnect-btn',
      'cloud-devices-dialog', 'cloud-devices-close-btn', 'cloud-devices-list',
      'cloud-recycle-dialog',
      'cloud-recycle-close-btn', 'cloud-recycle-list', 'delete-choice-dialog',
      'delete-choice-message', 'delete-release-local-btn', 'delete-move-trash-btn',
      'delete-choice-cancel-btn', 'hard-delete-dialog', 'hard-delete-password',
      'hard-delete-cancel-btn', 'hard-delete-confirm-btn', 'toast'
    ];
    ids.forEach(function (id) {
      el[toCamel(id)] = document.getElementById(id);
    });
  }

  function toCamel(value) {
    return value.replace(/-([a-z])/g, function (_, char) { return char.toUpperCase(); });
  }

  function bindEvents() {
    el.newRecordBtn.addEventListener('click', function () { openForm(); });
    el.emptyNewBtn.addEventListener('click', function () { openForm(); });
    el.inspectionTypeSettingsBtn.addEventListener('click', openInspectionTypeSettings);
    el.selectionModeBtn.addEventListener('click', toggleSelectionMode);
    el.selectionCloseBtn.addEventListener('click', exitSelectionMode);
    el.selectFilteredBtn.addEventListener('click', selectFilteredRecords);
    el.clearSelectionBtn.addEventListener('click', clearSelection);
    el.exportSelectedBtn.addEventListener('click', openExportChoice);
    el.exportCombinedBtn.addEventListener('click', function () { closeExportChoice(); exportSelectedRecords('combined'); });
    el.exportIndividualBtn.addEventListener('click', function () { closeExportChoice(); exportSelectedRecords('individual'); });
    el.exportChoiceCancelBtn.addEventListener('click', closeExportChoice);
    el.recordRangeSelect.addEventListener('change', onRecordRangeChange);
    el.recordTypeFilterSelect.addEventListener('change', function () {
      state.recordFilter.typeId = el.recordTypeFilterSelect.value;
      state.selectedIds.clear();
      renderHome();
    });
    el.recordFilterDay.addEventListener('change', function () {
      if (!el.recordFilterDay.value) return;
      setFilterDateParts(el.recordFilterDay.value);
      state.recordFilter.day = el.recordFilterDay.value;
      state.selectedIds.clear();
      renderHome();
    });
    el.recordFilterYear.addEventListener('change', function () { state.recordFilter.year = Number(el.recordFilterYear.value); state.selectedIds.clear(); renderHome(); });
    el.recordFilterMonth.addEventListener('change', function () { state.recordFilter.month = Number(el.recordFilterMonth.value); state.selectedIds.clear(); renderHome(); });
    el.recordFilterQuarter.addEventListener('change', function () { state.recordFilter.quarter = Number(el.recordFilterQuarter.value); state.selectedIds.clear(); renderHome(); });
    el.recordList.addEventListener('click', onRecordListClick);
    el.calendarPrevBtn.addEventListener('click', function () { changeCalendarMonth(-1); });
    el.calendarNextBtn.addEventListener('click', function () { changeCalendarMonth(1); });
    el.calendarTodayBtn.addEventListener('click', goCalendarToday);
    el.calendarSearchBtn.addEventListener('click', searchCalendarDate);
    el.calendarSearchDate.addEventListener('change', function () {
      if (el.calendarSearchDate.value) searchCalendarDate();
    });
    el.calendarGrid.addEventListener('click', onCalendarDayClick);
    el.syncExportBtn.addEventListener('click', exportSyncFile);
    el.syncImportBtn.addEventListener('click', function () { el.syncFileInput.click(); });
    el.syncFileInput.addEventListener('change', importSyncFile);
    el.cloudSetupBtn.addEventListener('click', openCloudSetup);
    el.cloudSetupCloseBtn.addEventListener('click', closeCloudSetup);
    el.cloudProviderSelect.addEventListener('change', onCloudProviderChanged);
    el.cloudEndpoint.addEventListener('input', resetCloudConnectionTest);
    el.cloudTestBtn.addEventListener('click', testCloudConnection);
    el.cloudSetupSubmitBtn.addEventListener('click', submitCloudSetup);
    el.cloudDisconnectBtn.addEventListener('click', disconnectCloudSync);
    el.cloudSyncNowBtn.addEventListener('click', function () { runCloudSync(true); });
    el.cloudDevicesBtn.addEventListener('click', openCloudDevices);
    el.cloudDevicesCloseBtn.addEventListener('click', function () { el.cloudDevicesDialog.classList.add('hidden'); });
    el.cloudDevicesList.addEventListener('click', onCloudDeviceListClick);
    el.cloudFailureRetryBtn.addEventListener('click', function () {
      clearCloudFailure();
      if (state.cloudService && state.cloudService.isConfigured()) runCloudSync(true);
      else openCloudSetup();
    });
    el.cloudFailureCloseBtn.addEventListener('click', clearCloudFailure);
    el.cloudRecycleBtn.addEventListener('click', openCloudRecycle);
    el.cloudRecycleCloseBtn.addEventListener('click', function () { el.cloudRecycleDialog.classList.add('hidden'); });
    el.cloudRecycleList.addEventListener('click', onCloudRecycleListClick);
    el.holidayUpdateBtn.addEventListener('click', function () { refreshHolidayYears([state.calendarYear, nowDate.getFullYear()], true); });
    el.deleteReleaseLocalBtn.addEventListener('click', releaseCurrentRecordLocal);
    el.deleteMoveTrashBtn.addEventListener('click', moveCurrentRecordToTrash);
    el.deleteChoiceCancelBtn.addEventListener('click', closeDeleteChoice);
    el.hardDeleteCancelBtn.addEventListener('click', closeHardDeleteDialog);
    el.hardDeleteConfirmBtn.addEventListener('click', confirmHardDeleteRecord);

    el.formBackBtn.addEventListener('click', goBackFromForm);
    el.saveTopBtn.addEventListener('click', function () { el.inspectionForm.requestSubmit(); });
    el.inspectionForm.addEventListener('submit', saveForm);
    el.inspectionType.addEventListener('change', onFormInspectionTypeChange);
    el.inspectionDate.addEventListener('input', syncFormMeta);
    el.inspectionLocation.addEventListener('input', syncFormMeta);
    el.rectificationOpinion.addEventListener('input', function () {
      if (state.formRecord) state.formRecord.rectification.opinion = el.rectificationOpinion.value;
    });
    el.rectificationCompleted.addEventListener('change', function () {
      if (!state.formRecord) return;
      state.formRecord.rectification.completed = el.rectificationCompleted.checked;
      state.formRecord.rectification.completedAt = el.rectificationCompleted.checked ? new Date().toISOString() : '';
      renderRectificationStatus();
    });

    el.inspectionPhotoBtn.addEventListener('click', function () { el.inspectionPhotoInput.click(); });
    el.rectificationPhotoBtn.addEventListener('click', function () { el.rectificationPhotoInput.click(); });
    el.inspectionPhotoInput.addEventListener('change', function (event) { handlePhotoFiles(event, 'inspection'); });
    el.rectificationPhotoInput.addEventListener('change', function (event) { handlePhotoFiles(event, 'rectification'); });
    el.inspectionPhotoGrid.addEventListener('click', onPhotoGridClick);
    el.rectificationPhotoGrid.addEventListener('click', onPhotoGridClick);

    document.querySelectorAll('[data-signature]').forEach(function (button) {
      button.addEventListener('click', function () { openSignature(button.dataset.signature); });
    });
    el.signatureCancelBtn.addEventListener('click', closeSignatureViaHistory);
    el.signatureSaveBtn.addEventListener('click', saveSignature);
    el.signatureClearBtn.addEventListener('click', clearSignatureCanvas);
    bindSignatureCanvas();

    el.deleteRecordBtn.addEventListener('click', deleteCurrentRecord);
    el.confirmCancelBtn.addEventListener('click', function () { resolveConfirm(false); });
    el.confirmOkBtn.addEventListener('click', function () { resolveConfirm(true); });
    el.inspectionTypeCloseBtn.addEventListener('click', closeInspectionTypeSettings);
    el.inspectionTypeAddBtn.addEventListener('click', addInspectionTypeDraft);
    el.inspectionTypeSaveBtn.addEventListener('click', saveInspectionTypeSettings);
    el.inspectionTypeList.addEventListener('input', onInspectionTypeDraftInput);
    el.inspectionTypeList.addEventListener('click', onInspectionTypeDraftClick);
    el.templateTypeSelect.addEventListener('change', function () {
      state.inspectionTemplateTypeId = el.templateTypeSelect.value;
      renderInspectionTemplateSettings();
    });
    el.inspectionTemplateList.addEventListener('input', onInspectionTemplateInput);
    el.inspectionTemplateList.addEventListener('click', onInspectionTemplateClick);
    el.inspectionTemplateAddBtn.addEventListener('click', addInspectionTemplateItem);

    window.addEventListener('popstate', onPopState);
    window.addEventListener('online', function () { runCloudSync(false); });
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) runCloudSync(false);
    });
    window.addEventListener('orientationchange', scheduleSignatureResize);
    window.addEventListener('resize', scheduleSignatureResize);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', scheduleSignatureResize);
  }

  function openDatabase() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise(function (resolve, reject) {
      const request = indexedDB.open(DB_NAME, 1);
      request.onupgradeneeded = function () {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
          store.createIndex('date', 'date', { unique: false });
          store.createIndex('updatedAt', 'updatedAt', { unique: false });
        }
      };
      request.onsuccess = function () { resolve(request.result); };
      request.onerror = function () { reject(request.error || new Error('无法打开本地数据库')); };
    });
    return dbPromise;
  }

  async function dbGetAll() {
    const db = await openDatabase();
    return new Promise(function (resolve, reject) {
      const transaction = db.transaction(STORE_NAME, 'readonly');
      const request = transaction.objectStore(STORE_NAME).getAll();
      request.onsuccess = function () { resolve(request.result || []); };
      request.onerror = function () { reject(request.error); };
    });
  }

  async function dbPut(record) {
    const db = await openDatabase();
    return new Promise(function (resolve, reject) {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      transaction.objectStore(STORE_NAME).put(record);
      transaction.oncomplete = function () { resolve(); };
      transaction.onerror = function () { reject(transaction.error); };
    });
  }

  async function dbDelete(id) {
    const db = await openDatabase();
    return new Promise(function (resolve, reject) {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      transaction.objectStore(STORE_NAME).delete(id);
      transaction.oncomplete = function () { resolve(); };
      transaction.onerror = function () { reject(transaction.error); };
    });
  }

  function loadInspectionTypes() {
    try {
      const parsed = JSON.parse(localStorage.getItem(INSPECTION_TYPES_KEY) || '[]');
      const sanitized = sanitizeInspectionTypes(parsed);
      if (sanitized.length) return sanitized;
    } catch (_) {}
    const defaults = deepClone(DEFAULT_INSPECTION_TYPES);
    persistInspectionTypes(defaults);
    return defaults;
  }

  function sanitizeInspectionTypes(types) {
    if (!Array.isArray(types)) return [];
    const names = new Set();
    return types.map(function (type) {
      if (!type || typeof type !== 'object') return null;
      const name = String(type.name || '').trim().slice(0, 30);
      if (!name || names.has(name)) return null;
      names.add(name);
      return { id: String(type.id || makeId('type')), name: name, items: sanitizeTemplateItems(type.items) };
    }).filter(Boolean);
  }

  function sanitizeTemplateItems(items) {
    const source = Array.isArray(items) && items.length ? items.slice(0, MAX_TEMPLATE_ITEMS) : CHECK_ITEMS;
    return source.map(function (raw, index) {
      const fallback = CHECK_ITEMS[index] || {
        category: '新的检查类别',
        standard: '请填写检查内容及标准'
      };
      raw = raw && typeof raw === 'object' ? raw : fallback;
      return {
        category: String(raw.category == null ? fallback.category : raw.category).trim().slice(0, 30),
        standard: String(raw.standard == null ? fallback.standard : raw.standard).trim().slice(0, 500)
      };
    });
  }

  function makeRecordItems(type) {
    return sanitizeTemplateItems(type && type.items).map(function (item, index) {
      return { sequence: index + 1, category: item.category, standard: item.standard, result: '', issue: '' };
    });
  }

  function persistInspectionTypes(types) {
    try { localStorage.setItem(INSPECTION_TYPES_KEY, JSON.stringify(types)); } catch (_) {}
  }

  function openInspectionTypeSettings() {
    state.inspectionTypeDraft = deepClone(state.inspectionTypes);
    state.inspectionTemplateTypeId = state.inspectionTypeDraft[0] ? state.inspectionTypeDraft[0].id : '';
    renderInspectionTypeSettings();
    el.inspectionTypeDialog.classList.remove('hidden');
  }

  function closeInspectionTypeSettings() {
    el.inspectionTypeDialog.classList.add('hidden');
    state.inspectionTypeDraft = [];
    state.inspectionTemplateTypeId = '';
  }

  function renderInspectionTypeSettings() {
    el.inspectionTypeList.innerHTML = state.inspectionTypeDraft.map(function (type) {
      return '<div class="inspection-type-row" data-type-id="' + escapeHtml(type.id) + '">' +
        '<input type="text" maxlength="30" value="' + escapeHtml(type.name) + '" aria-label="检查类型名称">' +
        '<button class="type-delete-btn" type="button" aria-label="删除检查类型">删除</button>' +
      '</div>';
    }).join('');
    renderInspectionTemplateSettings();
  }

  function renderInspectionTemplateSettings() {
    if (!state.inspectionTypeDraft.length) {
      el.templateTypeSelect.innerHTML = '';
      el.inspectionTemplateList.innerHTML = '';
      el.inspectionTemplateAddBtn.disabled = true;
      el.inspectionTemplateAddBtn.textContent = '＋ 新增检查项目';
      return;
    }
    if (!state.inspectionTypeDraft.some(function (type) { return type.id === state.inspectionTemplateTypeId; })) {
      state.inspectionTemplateTypeId = state.inspectionTypeDraft[0].id;
    }
    el.templateTypeSelect.innerHTML = state.inspectionTypeDraft.map(function (type) {
      return '<option value="' + escapeHtml(type.id) + '">' + escapeHtml(type.name || '未命名类型') + '</option>';
    }).join('');
    el.templateTypeSelect.value = state.inspectionTemplateTypeId;
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === state.inspectionTemplateTypeId; });
    const items = type ? sanitizeTemplateItems(type.items) : [];
    if (type) type.items = items;
    el.inspectionTemplateList.innerHTML = items.map(function (item, index) {
      return '<div class="inspection-template-row" data-template-index="' + index + '">' +
        '<span class="template-sequence">' + (index + 1) + '</span>' +
        '<input type="text" data-template-field="category" maxlength="30" value="' + escapeHtml(item.category) + '" aria-label="第' + (index + 1) + '项检查类别">' +
        '<textarea data-template-field="standard" maxlength="500" aria-label="第' + (index + 1) + '项检查内容及标准">' + escapeHtml(item.standard) + '</textarea>' +
        '<button class="template-delete-btn" type="button" aria-label="删除第' + (index + 1) + '项检查项目">删除</button>' +
      '</div>';
    }).join('');
    el.inspectionTemplateAddBtn.disabled = items.length >= MAX_TEMPLATE_ITEMS;
    el.inspectionTemplateAddBtn.textContent = items.length >= MAX_TEMPLATE_ITEMS ?
      '已达到最多 ' + MAX_TEMPLATE_ITEMS + ' 项' :
      '＋ 新增检查项目（当前 ' + items.length + '/' + MAX_TEMPLATE_ITEMS + ' 项）';
  }

  function addInspectionTypeDraft() {
    const sourceType = state.inspectionTypeDraft.find(function (type) { return type.id === state.inspectionTemplateTypeId; });
    const newType = { id: makeId('type'), name: '新的检查类型', items: sanitizeTemplateItems(sourceType && sourceType.items) };
    state.inspectionTypeDraft.push(newType);
    state.inspectionTemplateTypeId = newType.id;
    renderInspectionTypeSettings();
    const inputs = el.inspectionTypeList.querySelectorAll('input');
    const input = inputs[inputs.length - 1];
    if (input) { input.focus(); input.select(); }
  }

  function onInspectionTypeDraftInput(event) {
    const row = event.target.closest('[data-type-id]');
    if (!row || event.target.tagName !== 'INPUT') return;
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === row.dataset.typeId; });
    if (type) {
      type.name = event.target.value;
      const option = Array.from(el.templateTypeSelect.options).find(function (item) { return item.value === type.id; });
      if (option) option.textContent = type.name || '未命名类型';
    }
  }

  function onInspectionTemplateInput(event) {
    const row = event.target.closest('[data-template-index]');
    const field = event.target.dataset.templateField;
    if (!row || !field) return;
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === state.inspectionTemplateTypeId; });
    const index = Number(row.dataset.templateIndex);
    if (type && type.items && type.items[index]) type.items[index][field] = event.target.value;
  }

  function addInspectionTemplateItem() {
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === state.inspectionTemplateTypeId; });
    if (!type) return;
    type.items = sanitizeTemplateItems(type.items);
    if (type.items.length >= MAX_TEMPLATE_ITEMS) {
      showToast('每种检查类型最多设置 ' + MAX_TEMPLATE_ITEMS + ' 个检查项目');
      return;
    }
    type.items.push({ category: '新的检查类别', standard: '请填写检查内容及标准' });
    renderInspectionTemplateSettings();
    const newIndex = type.items.length - 1;
    const input = el.inspectionTemplateList.querySelector('[data-template-index="' + newIndex + '"] [data-template-field="category"]');
    if (input) { input.focus(); input.select(); }
  }

  function onInspectionTemplateClick(event) {
    const button = event.target.closest('.template-delete-btn');
    if (!button) return;
    const row = button.closest('[data-template-index]');
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === state.inspectionTemplateTypeId; });
    if (!row || !type || !Array.isArray(type.items)) return;
    if (type.items.length <= MIN_TEMPLATE_ITEMS) {
      showToast('每种检查类型至少保留一个检查项目');
      return;
    }
    type.items.splice(Number(row.dataset.templateIndex), 1);
    renderInspectionTemplateSettings();
  }

  function onInspectionTypeDraftClick(event) {
    const button = event.target.closest('.type-delete-btn');
    if (!button) return;
    const row = button.closest('[data-type-id]');
    if (!row) return;
    if (state.inspectionTypeDraft.length <= 1) {
      showToast('至少保留一个检查类型');
      return;
    }
    const type = state.inspectionTypeDraft.find(function (item) { return item.id === row.dataset.typeId; });
    const inUse = state.records.some(function (record) { return record.inspectionTypeId === row.dataset.typeId; });
    if (inUse) {
      showToast('“' + (type ? type.name : '该类型') + '”已有检查记录，不能删除，可直接修改名称');
      return;
    }
    state.inspectionTypeDraft = state.inspectionTypeDraft.filter(function (item) { return item.id !== row.dataset.typeId; });
    if (state.inspectionTemplateTypeId === row.dataset.typeId) {
      state.inspectionTemplateTypeId = state.inspectionTypeDraft[0] ? state.inspectionTypeDraft[0].id : '';
    }
    renderInspectionTypeSettings();
  }

  function saveInspectionTypeSettings() {
    const sanitized = sanitizeInspectionTypes(state.inspectionTypeDraft);
    if (!sanitized.length) {
      showToast('请至少填写一个检查类型');
      return;
    }
    const rawNames = state.inspectionTypeDraft.map(function (type) { return String(type.name || '').trim(); });
    if (sanitized.length !== state.inspectionTypeDraft.length || new Set(rawNames).size !== rawNames.length) {
      showToast('检查类型不能为空或重名');
      return;
    }
    const invalidTemplate = state.inspectionTypeDraft.find(function (type) {
      return !Array.isArray(type.items) || type.items.length < MIN_TEMPLATE_ITEMS || type.items.length > MAX_TEMPLATE_ITEMS || type.items.some(function (item) {
        return !String(item.category || '').trim() || !String(item.standard || '').trim();
      });
    });
    if (invalidTemplate) {
      showToast('“' + (invalidTemplate.name || '未命名类型') + '”的检查类别和检查内容不能为空');
      return;
    }
    state.inspectionTypes = sanitized;
    persistInspectionTypes(state.inspectionTypes);
    if (state.cloudService && state.cloudService.isConfigured()) {
      state.cloudService.markSettingsPending();
      setTimeout(function () { runCloudSync(false); }, 80);
    }
    if (state.recordFilter.typeId !== 'all' && !state.inspectionTypes.some(function (type) { return type.id === state.recordFilter.typeId; })) {
      state.recordFilter.typeId = 'all';
    }
    closeInspectionTypeSettings();
    renderHome();
    showToast('检查类型设置已保存');
  }

  function renderHome() {
    state.records.sort(function (a, b) {
      return String(b.date || '').localeCompare(String(a.date || '')) || String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''));
    });
    const activeRecords = state.records.filter(function (record) { return record.cloudStatus !== 'trash'; });
    const visibleRecords = getFilteredRecords();
    el.emptyState.classList.toggle('hidden', activeRecords.length !== 0);
    el.filteredEmptyState.classList.toggle('hidden', activeRecords.length === 0 || visibleRecords.length !== 0);
    el.recordList.classList.toggle('hidden', visibleRecords.length === 0);
    el.listSortLabel.textContent = getFilterLabel() + ' · ' + visibleRecords.length + ' 条';
    el.filteredEmptyTitle.textContent = getFilterLabel() + '没有检查记录';
    renderRecordFilterControls();
    renderCalendar();

    el.recordList.innerHTML = visibleRecords.map(function (record) {
      const status = getRecordStatus(record);
      const selected = state.selectedIds.has(record.id);
      const photoCount = (record.inspectionPhotos || []).length;
      const rectificationCount = record.rectification && record.rectification.photos ? record.rectification.photos.length : 0;
      const signatureCount = countSignatures(record);
      const syncMeta = getRecordSyncMeta(record);
      return '<article class="record-card ' + (state.selectionMode ? 'selection-enabled' : '') + (selected ? ' selected' : '') + (record.archiveOnly ? ' archive-record' : '') + (record.syncConflict ? ' sync-conflict' : '') + '" data-record-id="' + escapeHtml(record.id) + '">' +
        (state.selectionMode ? '<input class="record-select" type="checkbox" aria-label="选择本条记录" ' + (selected ? 'checked' : '') + '>' : '') +
        '<div class="record-main">' +
          '<span class="record-type-tag">' + escapeHtml(getRecordTypeName(record)) + '</span>' +
          '<div class="record-date"><strong>' + escapeHtml(formatDateZh(record.date)) + '</strong><span>' + escapeHtml(getQuarterLabel(record.date)) + '</span></div>' +
          '<p class="record-location">' + escapeHtml(record.archiveOnly ? '完整内容已保存为云端 PDF，可直接参与年度/季度合并导出' : (record.location || '未填写检查地点')) + '</p>' +
          '<div class="record-meta">' + (record.archiveOnly ? '<span class="cloud-archive">云端PDF归档</span>' : '<span>检查照片 ' + photoCount + ' 张</span><span>整改照片 ' + rectificationCount + ' 张</span><span class="' + (signatureCount < 3 ? 'missing-signature' : '') + '">签名 ' + signatureCount + '/3</span>') + '<span class="' + syncMeta.className + '">' + syncMeta.label + '</span></div>' +
        '</div>' +
        '<div class="record-side"><span class="status-pill ' + status.className + '">' + status.label + '</span><span class="record-arrow">›</span></div>' +
      '</article>';
    }).join('');
    updateSelectionUi();
  }

  function getFilteredRecords() {
    const filter = state.recordFilter;
    return state.records.filter(function (record) {
      if (record.cloudStatus === 'trash') return false;
      if (filter && filter.typeId && filter.typeId !== 'all' && record.inspectionTypeId !== filter.typeId) return false;
      if (!filter || filter.mode === 'all') return true;
      const value = String(record.date || '');
      if (filter.mode === 'day') return value === filter.day;
      if (filter.mode === 'year') return value.startsWith(String(filter.year) + '-');
      if (filter.mode === 'month') return value.startsWith(String(filter.year) + '-' + pad2(filter.month) + '-');
      if (filter.mode === 'quarter') {
        const date = parseLocalDate(value);
        return date.getFullYear() === Number(filter.year) && Math.floor(date.getMonth() / 3) + 1 === Number(filter.quarter);
      }
      return true;
    });
  }

  function getFilterLabel() {
    const filter = state.recordFilter;
    let rangeLabel = '全部记录';
    if (filter.mode === 'day') rangeLabel = formatDateZh(filter.day);
    if (filter.mode === 'month') rangeLabel = filter.year + '年' + pad2(filter.month) + '月';
    if (filter.mode === 'quarter') rangeLabel = filter.year + '年第' + filter.quarter + '季度';
    if (filter.mode === 'year') rangeLabel = filter.year + '年';
    if (filter.typeId && filter.typeId !== 'all') return rangeLabel + ' · ' + getTypeFilterName(filter.typeId);
    return rangeLabel;
  }

  function getTypeFilterName(typeId) {
    const configured = state.inspectionTypes.find(function (type) { return type.id === typeId; });
    if (configured) return configured.name;
    const record = state.records.find(function (item) { return item.inspectionTypeId === typeId; });
    return record ? getRecordTypeName(record) : '原检查类型';
  }

  function getAvailableYears() {
    const years = Array.from(new Set(state.records.map(function (record) { return Number(String(record.date || '').slice(0, 4)); }).filter(Boolean)));
    if (!years.includes(nowDate.getFullYear())) years.push(nowDate.getFullYear());
    if (!years.includes(Number(state.recordFilter.year))) years.push(Number(state.recordFilter.year));
    return years.sort(function (a, b) { return b - a; });
  }

  function renderRecordFilterControls() {
    const filter = state.recordFilter;
    const typeOptions = state.inspectionTypes.map(function (type) { return { id: type.id, name: type.name }; });
    state.records.forEach(function (record) {
      if (record.inspectionTypeId && !typeOptions.some(function (type) { return type.id === record.inspectionTypeId; })) {
        typeOptions.push({ id: record.inspectionTypeId, name: getRecordTypeName(record) });
      }
    });
    el.recordTypeFilterSelect.innerHTML = '<option value="all">全部检查类型</option>' + typeOptions.map(function (type) {
      return '<option value="' + escapeHtml(type.id) + '">' + escapeHtml(type.name) + '</option>';
    }).join('');
    if (filter.typeId !== 'all' && !typeOptions.some(function (type) { return type.id === filter.typeId; })) filter.typeId = 'all';
    el.recordTypeFilterSelect.value = filter.typeId || 'all';
    el.recordRangeSelect.value = filter.mode;
    el.recordFilterControls.dataset.mode = filter.mode;
    el.recordFilterControls.classList.toggle('hidden', filter.mode === 'all');
    el.recordFilterDayWrap.classList.toggle('hidden', filter.mode !== 'day');
    el.recordFilterYearWrap.classList.toggle('hidden', !['month', 'quarter', 'year'].includes(filter.mode));
    el.recordFilterMonthWrap.classList.toggle('hidden', filter.mode !== 'month');
    el.recordFilterQuarterWrap.classList.toggle('hidden', filter.mode !== 'quarter');
    el.recordFilterDay.value = filter.day;
    const years = getAvailableYears();
    el.recordFilterYear.innerHTML = years.map(function (year) { return '<option value="' + year + '">' + year + '年</option>'; }).join('');
    el.recordFilterYear.value = String(filter.year);
    if (!el.recordFilterMonth.options.length) {
      el.recordFilterMonth.innerHTML = Array.from({ length: 12 }, function (_, index) {
        return '<option value="' + (index + 1) + '">' + (index + 1) + '月</option>';
      }).join('');
    }
    el.recordFilterMonth.value = String(filter.month);
    el.recordFilterQuarter.value = String(filter.quarter);
  }

  function onRecordRangeChange() {
    state.recordFilter.mode = el.recordRangeSelect.value;
    if (state.recordFilter.mode !== 'all') setFilterDateParts(state.calendarSelectedDate || todayValue());
    state.selectedIds.clear();
    renderHome();
  }

  function setFilterDateParts(value) {
    const date = parseLocalDate(value);
    if (Number.isNaN(date.getTime())) return;
    state.recordFilter.day = dateKey(date);
    state.recordFilter.year = date.getFullYear();
    state.recordFilter.month = date.getMonth() + 1;
    state.recordFilter.quarter = Math.floor(date.getMonth() / 3) + 1;
  }

  function renderCalendar() {
    const year = state.calendarYear;
    const month = state.calendarMonth;
    el.calendarMonthTitle.textContent = year + '年' + (month + 1) + '月';

    const focusDate = parseLocalDate(state.calendarSelectedDate || todayValue());
    const focusLunar = getLunarMeta(focusDate);
    const focusHoliday = getHolidayMeta(focusDate);
    el.calendarFocusTitle.textContent = focusDate.getFullYear() + '年' + (focusDate.getMonth() + 1) + '月' + focusDate.getDate() + '日 ' + weekdayLabel(focusDate);
    el.calendarFocusLunar.textContent = '农历' + focusLunar.full + (focusHoliday ? ' · ' + focusHoliday.name + (focusHoliday.isWork ? '（调休上班）' : '') : '');

    const recordCounts = new Map();
    state.records.filter(function (record) {
      if (record.cloudStatus === 'trash') return false;
      return !state.recordFilter.typeId || state.recordFilter.typeId === 'all' || record.inspectionTypeId === state.recordFilter.typeId;
    }).forEach(function (record) {
      if (!record.date) return;
      recordCounts.set(record.date, (recordCounts.get(record.date) || 0) + 1);
    });

    const firstDay = new Date(year, month, 1);
    const startOffset = (firstDay.getDay() + 6) % 7;
    const startDate = new Date(year, month, 1 - startOffset);
    const today = todayValue();
    const cells = [];
    for (let index = 0; index < 42; index++) {
      const date = new Date(startDate.getFullYear(), startDate.getMonth(), startDate.getDate() + index);
      const key = dateKey(date);
      const lunar = getLunarMeta(date);
      const holiday = getHolidayMeta(date);
      const recordCount = recordCounts.get(key) || 0;
      const classes = ['calendar-day'];
      if (date.getMonth() !== month) classes.push('off-month');
      if (date.getDay() === 0) classes.push('sunday');
      if (holiday) classes.push(holiday.isWork ? 'holiday-work' : 'holiday-off');
      if (key === today) classes.push('today');
      if (key === state.calendarSelectedDate) classes.push('selected');
      if (recordCount) classes.push('has-record');
      const label = holiday && !holiday.isWork ? holiday.name : lunar.label;
      const tag = holiday ? '<span class="calendar-tag">' + (holiday.isWork ? '班' : '休') + '</span>' : '';
      const marker = recordCount ? '<span class="calendar-record-mark" title="有' + recordCount + '条检查记录"></span>' : '';
      const title = formatDateZh(key) + '，' + (holiday ? holiday.name + (holiday.isWork ? '调休上班' : '放假') + '，' : '') + (recordCount ? recordCount + '条检查记录' : '无检查记录');
      cells.push('<button class="' + classes.join(' ') + '" type="button" data-date="' + key + '" title="' + escapeHtml(title) + '" aria-label="' + escapeHtml(title) + '">' +
        '<span class="calendar-number">' + date.getDate() + '</span>' +
        '<span class="calendar-lunar">' + escapeHtml(label) + '</span>' + tag + marker +
      '</button>');
    }
    el.calendarGrid.innerHTML = cells.join('');
  }

  function changeCalendarMonth(offset) {
    const next = new Date(state.calendarYear, state.calendarMonth + offset, 1);
    state.calendarYear = next.getFullYear();
    state.calendarMonth = next.getMonth();
    state.calendarSelectedDate = dateKey(next);
    el.calendarSearchDate.value = state.calendarSelectedDate;
    renderHome();
    refreshHolidayYears([state.calendarYear], false);
  }

  function goCalendarToday() {
    applyCalendarDate(todayValue(), true);
  }

  function searchCalendarDate() {
    if (!el.calendarSearchDate.value) {
      showToast('请先选择要查找的日期');
      return;
    }
    applyCalendarDate(el.calendarSearchDate.value, true);
  }

  function onCalendarDayClick(event) {
    const button = event.target.closest('[data-date]');
    if (!button) return;
    applyCalendarDate(button.dataset.date, true);
  }

  function applyCalendarDate(value, filterRecords) {
    const date = parseLocalDate(value);
    if (Number.isNaN(date.getTime())) return;
    state.calendarYear = date.getFullYear();
    state.calendarMonth = date.getMonth();
    state.calendarSelectedDate = dateKey(date);
    if (filterRecords) {
      setFilterDateParts(state.calendarSelectedDate);
      state.recordFilter.mode = 'day';
      state.selectedIds.clear();
    }
    el.calendarSearchDate.value = state.calendarSelectedDate;
    renderHome();
    refreshHolidayYears([state.calendarYear], false);
    setTimeout(function () {
      const heading = document.querySelector('.records-section');
      if (heading) heading.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 50);
  }

  function clearDateFilter() {
    state.recordFilter.mode = 'all';
    state.selectedIds.clear();
    renderHome();
  }

  function getLunarMeta(date) {
    try {
      if (window.Solar && typeof window.Solar.fromYmd === 'function') {
        const lunar = window.Solar.fromYmd(date.getFullYear(), date.getMonth() + 1, date.getDate()).getLunar();
        const month = lunar.getMonthInChinese() + '月';
        const day = lunar.getDayInChinese();
        const jieQi = lunar.getJieQi();
        return { full: month + day, label: jieQi || (day === '初一' ? month : day) };
      }
    } catch (error) {
      console.warn('农历转换失败', error);
    }
    return { full: '', label: '' };
  }

  function getHolidayMeta(date) {
    const key = dateKey(date);
    const cachedYear = state.onlineHolidays && state.onlineHolidays[String(date.getFullYear())];
    const onlineData = cachedYear && cachedYear.data;
    if (onlineData) {
      const workValue = onlineData.workdays && onlineData.workdays[key];
      const holidayValue = onlineData.holidays && onlineData.holidays[key];
      const raw = workValue || holidayValue;
      if (raw) {
        const parts = String(raw).split(',');
        return { name: parts[1] || parts[0] || '法定节假日', isWork: Boolean(workValue) };
      }
    }
    try {
      if (!window.HolidayUtil || typeof window.HolidayUtil.getHoliday !== 'function') return null;
      const holiday = window.HolidayUtil.getHoliday(date.getFullYear(), date.getMonth() + 1, date.getDate());
      if (!holiday) return null;
      return { name: holiday.getName(), isWork: Boolean(holiday.isWork()) };
    } catch (_) {
      return null;
    }
  }

  function weekdayLabel(date) {
    return ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][date.getDay()];
  }

  function countSignatures(record) {
    const signatures = record && record.signatures ? record.signatures : {};
    return ['inspector1', 'inspector2', 'inspected'].filter(function (key) { return Boolean(signatures[key]); }).length;
  }

  function getRecordStatus(record) {
    if (record && record.archiveOnly) return { label: '云端归档', className: 'normal' };
    if (!hasIssues(record)) return { label: '检查完成', className: 'normal' };
    if (record.rectification && record.rectification.completed) return { label: '已整改', className: 'complete' };
    return { label: '待整改', className: 'pending' };
  }

  function hasIssues(record) {
    return Boolean(record && record.items && record.items.some(function (item) { return item.result === 'no'; }));
  }

  function getRecordSyncMeta(record) {
    if (!state.cloudService || !state.cloudService.isConfigured()) return { label: '仅本机', className: '' };
    if (record.syncConflict) return { label: '冲突副本待确认', className: 'sync-pending' };
    if (record.syncPending || !record.cloudTracked) return { label: '等待云同步', className: 'sync-pending' };
    if (record.cloudStatus === 'archived') return { label: '云端已归档', className: 'cloud-archive' };
    const currentDeviceId = state.cloudService.getSummary().deviceId;
    return { label: record.sourceDeviceId && record.sourceDeviceId !== currentDeviceId ? '来自其他设备 · 已同步' : '本设备 · 已同步', className: 'sync-ok' };
  }

  function onRecordListClick(event) {
    const card = event.target.closest('.record-card');
    if (!card) return;
    const id = card.dataset.recordId;
    if (state.selectionMode) {
      toggleSelected(id);
      return;
    }
    const record = state.records.find(function (item) { return item.id === id; });
    if (record && record.archiveOnly) {
      downloadArchivedRecordPdf(record);
      return;
    }
    openForm(id);
  }

  function enterSelectionMode() {
    if (!getFilteredRecords().length) {
      showToast('还没有可导出的检查记录');
      return;
    }
    state.selectionMode = true;
    state.selectedIds.clear();
    renderHome();
  }

  function toggleSelectionMode() {
    if (state.selectionMode) exitSelectionMode();
    else enterSelectionMode();
  }

  function exitSelectionMode() {
    state.selectionMode = false;
    state.selectedIds.clear();
    renderHome();
  }

  function toggleSelected(id) {
    if (state.selectedIds.has(id)) state.selectedIds.delete(id);
    else state.selectedIds.add(id);
    renderHome();
  }

  function selectFilteredRecords() {
    const visibleRecords = getFilteredRecords();
    const allSelected = visibleRecords.length > 0 && visibleRecords.every(function (record) {
      return state.selectedIds.has(record.id);
    });
    visibleRecords.forEach(function (record) {
      if (allSelected) state.selectedIds.delete(record.id);
      else state.selectedIds.add(record.id);
    });
    renderHome();
    showToast(allSelected ? '已取消当前显示记录的选择' : '已全选当前显示的 ' + visibleRecords.length + ' 条记录');
  }

  function clearSelection() {
    state.selectedIds.clear();
    renderHome();
  }

  function updateSelectionUi() {
    const count = state.selectedIds.size;
    const visibleRecords = getFilteredRecords();
    const visibleCount = visibleRecords.length;
    const allVisibleSelected = visibleCount > 0 && visibleRecords.every(function (record) {
      return state.selectedIds.has(record.id);
    });
    el.selectionActionsPanel.classList.toggle('hidden', !state.selectionMode);
    el.selectionModeBtn.classList.toggle('active', state.selectionMode);
    el.selectionModeBtn.textContent = state.selectionMode ? '退出多选' : '多选导出';
    el.selectionModeBtn.setAttribute('aria-pressed', state.selectionMode ? 'true' : 'false');
    el.selectionHint.textContent = '已选 ' + count + ' 条';
    el.selectionScopeHint.textContent = '当前显示 ' + visibleCount + ' 条';
    el.selectFilteredBtn.textContent = allVisibleSelected ? '取消全选（当前 ' + visibleCount + ' 条）' : '全选当前显示（' + visibleCount + ' 条）';
    el.selectFilteredBtn.disabled = visibleCount === 0;
    el.exportSelectedBtn.disabled = count === 0;
  }

  function blankRecord() {
    const now = new Date().toISOString();
    const defaultType = state.inspectionTypes[0] || DEFAULT_INSPECTION_TYPES[0];
    return {
      id: '',
      inspectionTypeId: defaultType.id,
      inspectionTypeName: defaultType.name,
      date: todayValue(),
      location: '',
      items: makeRecordItems(defaultType),
      inspectionPhotos: [],
      signatures: { inspector1: '', inspector2: '', inspected: '' },
      rectification: { opinion: '', photos: [], completed: false, completedAt: '' },
      createdAt: now,
      updatedAt: now
    };
  }

  function openForm(id) {
    const existing = id ? state.records.find(function (record) { return record.id === id; }) : null;
    state.formRecord = existing ? deepClone(existing) : blankRecord();
    normalizeRecord(state.formRecord);
    state.editingId = existing ? existing.id : null;
    el.formTitle.textContent = existing ? '查看与补录' : '检查填报';
    renderInspectionTypeOptions(state.formRecord);
    el.inspectionDate.value = state.formRecord.date || todayValue();
    el.inspectionLocation.value = state.formRecord.location || '';
    el.rectificationOpinion.value = state.formRecord.rectification.opinion || '';
    el.rectificationCompleted.checked = Boolean(state.formRecord.rectification.completed);
    el.deleteRecordBtn.classList.toggle('hidden', !existing);
    renderForm();
    showView('form');
    history.pushState({ view: 'form' }, '', '#form');
    window.scrollTo(0, 0);
  }

  function normalizeRecord(record) {
    record.cloudVersion = Number(record.cloudVersion || 0);
    record.cloudTracked = Boolean(record.cloudTracked);
    record.cloudStatus = record.cloudStatus || 'active';
    record.syncPending = Boolean(record.syncPending);
    record.archiveOnly = Boolean(record.archiveOnly);
    record.archiveBlobId = record.archiveBlobId || '';
    record.archivePageStart = Number(record.archivePageStart || 0);
    record.archivePageCount = Number(record.archivePageCount || 0);
    record.sourceDeviceId = record.sourceDeviceId || '';
    record.deletedAt = record.deletedAt || '';
    const matchingType = state.inspectionTypes.find(function (type) {
      return type.id === record.inspectionTypeId || (!record.inspectionTypeId && type.name === record.inspectionTypeName);
    });
    const fallbackType = matchingType || state.inspectionTypes[0] || DEFAULT_INSPECTION_TYPES[0];
    record.inspectionTypeId = record.inspectionTypeId || fallbackType.id;
    record.inspectionTypeName = record.inspectionTypeName || fallbackType.name;
    if (record.archiveOnly) {
      record.items = [];
      record.inspectionPhotos = [];
      record.signatures = Object.assign({ inspector1: '', inspector2: '', inspected: '' }, record.signatures || {});
      record.rectification = Object.assign({ opinion: '', photos: [], completed: true, completedAt: '' }, record.rectification || {});
      record.rectification.photos = [];
      return;
    }
    if (!Array.isArray(record.items) || !record.items.length) {
      record.items = makeRecordItems(fallbackType);
    } else {
      record.items = record.items.map(function (item, index) {
        item = item && typeof item === 'object' ? item : {};
        return {
          sequence: index + 1,
          category: String(item.category || ('检查类别' + (index + 1))).slice(0, 30),
          standard: String(item.standard || '').slice(0, 500),
          result: item.result === 'yes' || item.result === 'no' || item.result === 'na' ? item.result : '',
          issue: String(item.issue || '').slice(0, 500)
        };
      });
    }
    record.inspectionPhotos = record.inspectionPhotos || [];
    record.signatures = Object.assign({ inspector1: '', inspector2: '', inspected: '' }, record.signatures || {});
    record.rectification = Object.assign({ opinion: '', photos: [], completed: false, completedAt: '' }, record.rectification || {});
    record.rectification.photos = record.rectification.photos || [];
  }

  function renderForm() {
    el.inspectionSheetTitle.textContent = getSheetTitle(state.formRecord);
    renderInspectionItems();
    renderPhotoGrid('inspection');
    renderPhotoGrid('rectification');
    renderSignatures();
    updateRectificationVisibility();
  }

  function renderInspectionTypeOptions(record) {
    const options = state.inspectionTypes.slice();
    if (record && record.inspectionTypeId && !options.some(function (type) { return type.id === record.inspectionTypeId; })) {
      options.push({ id: record.inspectionTypeId, name: record.inspectionTypeName || '原检查类型', items: sanitizeTemplateItems(record.items) });
    }
    el.inspectionType.innerHTML = options.map(function (type) {
      return '<option value="' + escapeHtml(type.id) + '">' + escapeHtml(type.name) + '</option>';
    }).join('');
    el.inspectionType.value = record && record.inspectionTypeId ? record.inspectionTypeId : (options[0] ? options[0].id : '');
  }

  function getRecordTypeName(record) {
    const matching = state.inspectionTypes.find(function (type) { return record && type.id === record.inspectionTypeId; });
    return matching ? matching.name : String(record && record.inspectionTypeName || DEFAULT_INSPECTION_TYPES[0].name);
  }

  function getSheetTitle(record) {
    const name = getRecordTypeName(record).trim();
    return name.endsWith('表') ? name : name + '表';
  }

  function getPhotoAppendixTitle(record) {
    const name = getRecordTypeName(record).trim().replace(/表$/, '');
    return name + '照片附件';
  }

  function renderInspectionItems() {
    if (!state.formRecord) return;
    el.inspectionItems.innerHTML = state.formRecord.items.map(function (item, index) {
      const noSelected = item.result === 'no';
      return '<div class="inspection-item" data-item-index="' + index + '">' +
        '<div class="item-content">' +
          '<span class="item-category">' + escapeHtml(item.category) + '</span>' +
          '<span class="item-sequence">' + (index + 1) + '</span>' +
          '<div class="item-standard">' + escapeHtml(item.standard) + '</div>' +
        '</div>' +
        '<div class="item-result">' +
          '<label class="result-option yes' + (item.result === 'yes' ? ' selected' : '') + '"><input type="radio" name="result-' + index + '" value="yes" ' + (item.result === 'yes' ? 'checked' : '') + '>是</label>' +
          '<label class="result-option no' + (item.result === 'no' ? ' selected' : '') + '"><input type="radio" name="result-' + index + '" value="no" ' + (item.result === 'no' ? 'checked' : '') + '>否</label>' +
          '<label class="result-option na' + (item.result === 'na' ? ' selected' : '') + '"><input type="radio" name="result-' + index + '" value="na" ' + (item.result === 'na' ? 'checked' : '') + '>不适用</label>' +
        '</div>' +
        (noSelected ? '<div class="issue-editor"><label>现场情况/问题</label><textarea maxlength="500" placeholder="请填写发现的问题和整改要求">' + escapeHtml(item.issue || '') + '</textarea></div>' : '') +
      '</div>';
    }).join('');
    el.inspectionItems.querySelectorAll('input[type="radio"]').forEach(function (input) {
      input.addEventListener('change', function () {
        const index = Number(input.closest('.inspection-item').dataset.itemIndex);
        state.formRecord.items[index].result = input.value;
        if (input.value !== 'no') state.formRecord.items[index].issue = '';
        renderInspectionItems();
        updateRectificationVisibility();
      });
    });
    el.inspectionItems.querySelectorAll('.issue-editor textarea').forEach(function (textarea) {
      textarea.addEventListener('input', function () {
        const index = Number(textarea.closest('.inspection-item').dataset.itemIndex);
        state.formRecord.items[index].issue = textarea.value;
      });
    });
    const completed = state.formRecord.items.filter(function (item) { return item.result === 'yes' || item.result === 'no' || item.result === 'na'; }).length;
    el.itemsProgress.textContent = completed + '/' + state.formRecord.items.length;
  }

  function updateRectificationVisibility() {
    const visible = hasIssues(state.formRecord);
    el.rectificationSection.classList.toggle('hidden', !visible);
    el.signatureStepNumber.textContent = visible ? '4' : '3';
    renderRectificationStatus();
  }

  function renderRectificationStatus() {
    if (!state.formRecord) return;
    const completed = Boolean(state.formRecord.rectification.completed);
    el.rectificationStatusPill.textContent = completed ? '已整改' : '待整改';
    el.rectificationStatusPill.className = 'status-pill ' + (completed ? 'complete' : 'pending');
  }

  function syncFormMeta() {
    if (!state.formRecord) return;
    const selectedType = state.inspectionTypes.find(function (type) { return type.id === el.inspectionType.value; });
    if (selectedType) {
      state.formRecord.inspectionTypeId = selectedType.id;
      state.formRecord.inspectionTypeName = selectedType.name;
    }
    state.formRecord.date = el.inspectionDate.value;
    state.formRecord.location = el.inspectionLocation.value.trim();
    el.inspectionSheetTitle.textContent = getSheetTitle(state.formRecord);
  }

  function onFormInspectionTypeChange() {
    if (!state.formRecord) return;
    const previousTypeId = state.formRecord.inspectionTypeId;
    const selectedType = state.inspectionTypes.find(function (type) { return type.id === el.inspectionType.value; });
    if (!selectedType) return;
    state.formRecord.inspectionTypeId = selectedType.id;
    state.formRecord.inspectionTypeName = selectedType.name;
    if (!state.editingId && previousTypeId !== selectedType.id) {
      state.formRecord.items = makeRecordItems(selectedType);
      showToast('已切换为“' + selectedType.name + '”检查项目模板');
    }
    syncFormMeta();
    renderForm();
  }

  async function handlePhotoFiles(event, type) {
    const files = Array.from(event.target.files || []);
    event.target.value = '';
    if (!files.length || !state.formRecord) return;
    syncFormMeta();
    const locationName = state.formRecord.location || '未填写检查地点';
    showProgress('正在处理照片', '正在获取时间、地点并添加水印…', 5);
    let gpsLabel = '';
    try { gpsLabel = await getGpsLabel(); } catch (_) { gpsLabel = ''; }
    const destination = type === 'inspection' ? state.formRecord.inspectionPhotos : state.formRecord.rectification.photos;
    let added = 0;
    try {
      for (let index = 0; index < files.length; index++) {
        setProgress('正在处理第 ' + (index + 1) + '/' + files.length + ' 张照片', Math.round(((index + 0.2) / files.length) * 100));
        try {
          const photo = await watermarkPhoto(files[index], locationName, gpsLabel);
          destination.push(photo);
          added++;
        } catch (error) {
          console.error(error);
        }
      }
      renderPhotoGrid(type);
      setProgress('照片已保存到本条记录', 100);
      setTimeout(hideProgress, 280);
      showToast('已加入 ' + added + ' 张带水印照片');
    } catch (error) {
      hideProgress();
      showToast('照片处理失败，请重试');
    }
  }

  async function getGpsLabel() {
    try {
      if (window.android && typeof window.android.requestPermission === 'function') {
        window.android.requestPermission('ACCESS_FINE_LOCATION');
      }
    } catch (_) {}
    await wait(650);
    if (!navigator.geolocation) return '';
    return new Promise(function (resolve) {
      navigator.geolocation.getCurrentPosition(function (position) {
        const latitude = position.coords.latitude.toFixed(5);
        const longitude = position.coords.longitude.toFixed(5);
        resolve('GPS ' + latitude + ', ' + longitude);
      }, function () { resolve(''); }, { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 });
    });
  }

  async function watermarkPhoto(file, locationName, gpsLabel) {
    const image = await loadImageFile(file);
    const maxSide = 1600;
    const scale = Math.min(1, maxSide / Math.max(image.naturalWidth || image.width, image.naturalHeight || image.height));
    const width = Math.max(1, Math.round((image.naturalWidth || image.width) * scale));
    const height = Math.max(1, Math.round((image.naturalHeight || image.height) * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0, width, height);

    const capturedAt = new Date();
    const overlayHeight = Math.max(92, Math.min(164, Math.round(height * 0.15)));
    const padding = Math.max(14, Math.round(width * 0.018));
    const fontSize = Math.max(18, Math.min(34, Math.round(width / 42)));
    const lineHeight = Math.round(fontSize * 1.45);
    const gradient = context.createLinearGradient(0, height - overlayHeight, 0, height);
    gradient.addColorStop(0, 'rgba(0,0,0,0.18)');
    gradient.addColorStop(0.24, 'rgba(0,0,0,0.64)');
    gradient.addColorStop(1, 'rgba(0,0,0,0.82)');
    context.fillStyle = gradient;
    context.fillRect(0, height - overlayHeight, width, overlayHeight);
    context.fillStyle = '#ffffff';
    context.font = '600 ' + fontSize + 'px sans-serif';
    context.textBaseline = 'top';
    const timeText = '时间：' + formatDateTimeZh(capturedAt);
    const locationText = '地点：' + locationName + (gpsLabel ? '  ·  ' + gpsLabel : '');
    context.fillText(truncateCanvasText(context, timeText, width - padding * 2), padding, height - overlayHeight + Math.max(10, padding * 0.7));
    context.font = '500 ' + Math.max(16, fontSize - 2) + 'px sans-serif';
    context.fillText(truncateCanvasText(context, locationText, width - padding * 2), padding, height - overlayHeight + Math.max(10, padding * 0.7) + lineHeight);
    return {
      id: makeId('photo'),
      data: canvas.toDataURL('image/jpeg', 0.78),
      capturedAt: capturedAt.toISOString(),
      location: locationName,
      gps: gpsLabel
    };
  }

  function loadImageFile(file) {
    return new Promise(function (resolve, reject) {
      const url = URL.createObjectURL(file);
      const image = new Image();
      image.onload = function () { URL.revokeObjectURL(url); resolve(image); };
      image.onerror = function () { URL.revokeObjectURL(url); reject(new Error('不支持此照片格式')); };
      image.src = url;
    });
  }

  function truncateCanvasText(context, text, maxWidth) {
    if (context.measureText(text).width <= maxWidth) return text;
    let result = text;
    while (result.length > 1 && context.measureText(result + '…').width > maxWidth) result = result.slice(0, -1);
    return result + '…';
  }

  function renderPhotoGrid(type) {
    if (!state.formRecord) return;
    const photos = type === 'inspection' ? state.formRecord.inspectionPhotos : state.formRecord.rectification.photos;
    const grid = type === 'inspection' ? el.inspectionPhotoGrid : el.rectificationPhotoGrid;
    const label = type === 'inspection' ? '检查照片' : '整改照片';
    grid.innerHTML = photos.map(function (photo, index) {
      return '<figure class="photo-card"><img src="' + photo.data + '" alt="' + label + (index + 1) + '"><span class="photo-label">' + label + ' ' + (index + 1) + '</span><button class="photo-delete" type="button" data-photo-type="' + type + '" data-photo-index="' + index + '" aria-label="删除照片">×</button></figure>';
    }).join('');
    el.inspectionPhotoCount.textContent = state.formRecord.inspectionPhotos.length + ' 张';
  }

  function onPhotoGridClick(event) {
    const button = event.target.closest('.photo-delete');
    if (!button || !state.formRecord) return;
    const index = Number(button.dataset.photoIndex);
    const type = button.dataset.photoType;
    const photos = type === 'inspection' ? state.formRecord.inspectionPhotos : state.formRecord.rectification.photos;
    photos.splice(index, 1);
    renderPhotoGrid(type);
  }

  function renderSignatures() {
    if (!state.formRecord) return;
    const labels = ['inspector1', 'inspector2', 'inspected'];
    let count = 0;
    labels.forEach(function (key) {
      const preview = document.getElementById('signature-preview-' + key);
      const data = state.formRecord.signatures[key];
      if (data) {
        preview.innerHTML = '<img src="' + data + '" alt="已签名">';
        count++;
      } else {
        preview.textContent = '＋';
      }
    });
    el.signatureProgress.textContent = count + '/3';
  }

  function openSignature(target) {
    if (!state.formRecord || state.signatureOpen) return;
    state.signatureTarget = target;
    state.signatureOpen = true;
    state.signatureHasInk = false;
    const names = { inspector1: '检查人签名 1', inspector2: '检查人签名 2', inspected: '被检查人签名' };
    el.signatureModalTitle.textContent = names[target];
    el.signatureModal.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    try { if (window.android && window.android.enterSignature) window.android.enterSignature(); } catch (_) {}
    history.pushState({ view: 'signature' }, '', '#signature');
    setTimeout(function () {
      resizeSignatureCanvas(false);
      const existing = state.formRecord.signatures[target];
      if (existing) drawSignatureData(existing);
      else clearSignatureCanvas();
    }, 300);
  }

  function closeSignatureViaHistory() {
    if (state.signatureOpen) history.back();
  }

  function closeSignature() {
    if (!state.signatureOpen) return;
    state.signatureOpen = false;
    state.signatureTarget = null;
    el.signatureModal.classList.add('hidden');
    document.body.style.overflow = '';
    try { if (window.android && window.android.exitSignature) window.android.exitSignature(); } catch (_) {}
  }

  function saveSignature() {
    if (!state.signatureHasInk) {
      showToast('请先完成手写签名');
      return;
    }
    const canvas = el.signatureCanvas;
    state.formRecord.signatures[state.signatureTarget] = exportTrimmedSignature(canvas);
    renderSignatures();
    history.back();
  }

  function bindSignatureCanvas() {
    const canvas = el.signatureCanvas;
    canvas.addEventListener('pointerdown', function (event) {
      event.preventDefault();
      signatureDrawing = true;
      signaturePointerId = event.pointerId;
      try { canvas.setPointerCapture(event.pointerId); } catch (_) {}
      signatureLastPoint = signaturePoint(event);
      signatureLastMidPoint = signatureLastPoint;
      markSignatureInk();
      const context = canvas.getContext('2d');
      const strokeWidth = signatureStrokeWidth(canvas, event);
      context.beginPath();
      context.arc(signatureLastPoint.x, signatureLastPoint.y, strokeWidth * 0.52, 0, Math.PI * 2);
      context.fillStyle = '#07111f';
      context.fill();
    });
    canvas.addEventListener('pointermove', function (event) {
      if (!signatureDrawing || event.pointerId !== signaturePointerId) return;
      event.preventDefault();
      const events = event.getCoalescedEvents ? event.getCoalescedEvents() : [event];
      events.forEach(function (pointEvent) {
        const point = signaturePoint(pointEvent);
        const middle = signatureMidPoint(signatureLastPoint, point);
        const context = canvas.getContext('2d');
        context.beginPath();
        context.moveTo(signatureLastMidPoint.x, signatureLastMidPoint.y);
        context.quadraticCurveTo(signatureLastPoint.x, signatureLastPoint.y, middle.x, middle.y);
        context.strokeStyle = '#07111f';
        context.lineWidth = signatureStrokeWidth(canvas, pointEvent);
        context.lineCap = 'round';
        context.lineJoin = 'round';
        context.stroke();
        signatureLastMidPoint = middle;
        signatureLastPoint = point;
      });
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (name) {
      canvas.addEventListener(name, function (event) {
        if (event.pointerId === signaturePointerId) {
          if (name === 'pointerup' && signatureLastMidPoint && signatureLastPoint) {
            const context = canvas.getContext('2d');
            context.beginPath();
            context.moveTo(signatureLastMidPoint.x, signatureLastMidPoint.y);
            context.lineTo(signatureLastPoint.x, signatureLastPoint.y);
            context.strokeStyle = '#07111f';
            context.lineWidth = signatureStrokeWidth(canvas, event);
            context.lineCap = 'round';
            context.lineJoin = 'round';
            context.stroke();
          }
          signatureDrawing = false;
          signaturePointerId = null;
          signatureLastPoint = null;
          signatureLastMidPoint = null;
        }
      });
    });
  }

  function signatureMidPoint(first, second) {
    return { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
  }

  function signatureStrokeWidth(canvas, event) {
    const rect = canvas.getBoundingClientRect();
    const pixelScale = rect.width > 0 ? canvas.width / rect.width : 1;
    let pressureScale = 1;
    if (event && event.pointerType === 'pen' && event.pressure > 0) {
      pressureScale = 0.9 + Math.min(1, event.pressure) * 0.25;
    }
    return 4.2 * pixelScale * pressureScale;
  }

  function exportTrimmedSignature(canvas) {
    const context = canvas.getContext('2d');
    let pixels;
    try {
      pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    } catch (_) {
      return canvas.toDataURL('image/png');
    }
    let minX = canvas.width;
    let minY = canvas.height;
    let maxX = -1;
    let maxY = -1;
    for (let y = 0; y < canvas.height; y++) {
      for (let x = 0; x < canvas.width; x++) {
        if (pixels[(y * canvas.width + x) * 4 + 3] > 8) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < minX || maxY < minY) return canvas.toDataURL('image/png');
    const rect = canvas.getBoundingClientRect();
    const pixelScale = rect.width > 0 ? canvas.width / rect.width : 1;
    const contentWidth = maxX - minX + 1;
    const contentHeight = maxY - minY + 1;
    const padding = Math.round(Math.max(10 * pixelScale, Math.min(24 * pixelScale, Math.max(contentWidth, contentHeight) * 0.08)));
    const sourceX = Math.max(0, minX - padding);
    const sourceY = Math.max(0, minY - padding);
    const sourceWidth = Math.min(canvas.width - sourceX, contentWidth + padding * 2);
    const sourceHeight = Math.min(canvas.height - sourceY, contentHeight + padding * 2);
    const output = document.createElement('canvas');
    output.width = sourceWidth;
    output.height = sourceHeight;
    output.getContext('2d').drawImage(canvas, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, sourceWidth, sourceHeight);
    return output.toDataURL('image/png');
  }

  function signaturePoint(event) {
    const canvas = el.signatureCanvas;
    const rect = canvas.getBoundingClientRect();
    return {
      x: (event.clientX - rect.left) * (canvas.width / rect.width),
      y: (event.clientY - rect.top) * (canvas.height / rect.height)
    };
  }

  function resizeSignatureCanvas(preserve) {
    if (!state.signatureOpen) return;
    const canvas = el.signatureCanvas;
    const rect = canvas.parentElement.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) return;
    let snapshot = null;
    if (preserve && canvas.width && canvas.height && state.signatureHasInk) {
      snapshot = document.createElement('canvas');
      snapshot.width = canvas.width;
      snapshot.height = canvas.height;
      snapshot.getContext('2d').drawImage(canvas, 0, 0);
    }
    const ratio = Math.min(2, Math.max(1, window.devicePixelRatio || 1));
    canvas.width = Math.round(rect.width * ratio);
    canvas.height = Math.round(rect.height * ratio);
    if (snapshot) canvas.getContext('2d').drawImage(snapshot, 0, 0, snapshot.width, snapshot.height, 0, 0, canvas.width, canvas.height);
  }

  function scheduleSignatureResize() {
    if (!state.signatureOpen) return;
    clearTimeout(signatureResizeTimer);
    signatureResizeTimer = setTimeout(function () { resizeSignatureCanvas(true); }, 180);
  }

  function clearSignatureCanvas() {
    const canvas = el.signatureCanvas;
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
    state.signatureHasInk = false;
    canvas.parentElement.classList.remove('has-ink');
  }

  function markSignatureInk() {
    state.signatureHasInk = true;
    el.signatureCanvas.parentElement.classList.add('has-ink');
  }

  function drawSignatureData(dataUrl) {
    const image = new Image();
    image.onload = function () {
      const canvas = el.signatureCanvas;
      const context = canvas.getContext('2d');
      context.clearRect(0, 0, canvas.width, canvas.height);
      const scale = Math.min(canvas.width / image.width, canvas.height / image.height);
      const width = image.width * scale;
      const height = image.height * scale;
      context.drawImage(image, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height);
      markSignatureInk();
    };
    image.src = dataUrl;
  }

  async function saveForm(event) {
    event.preventDefault();
    if (!state.formRecord) return;
    syncFormMeta();
    state.formRecord.rectification.opinion = el.rectificationOpinion.value.trim();
    const error = validateRecord(state.formRecord);
    if (error) {
      showToast(error);
      return;
    }
    const missingSignatures = getMissingSignatureLabels(state.formRecord);
    if (missingSignatures.length) {
      const confirmed = await showConfirm(
        '签名未完成',
        '尚未完成：' + missingSignatures.join('、') + '。确认后仍可保存，导出的 PDF 会保留空白签名栏，方便打印后手写签名。',
        '仍然保存'
      );
      if (!confirmed) return;
    }
    const now = new Date().toISOString();
    if (!state.formRecord.id) {
      state.formRecord.id = makeId('record');
      state.formRecord.createdAt = now;
    }
    state.formRecord.updatedAt = now;
    if (state.cloudService && state.cloudService.isConfigured()) {
      state.formRecord.syncPending = true;
      state.formRecord.cloudStatus = 'active';
      state.formRecord.archiveOnly = false;
    }
    const savedRecordId = state.formRecord.id;
    showProgress('正在保存', '检查记录和照片正在写入本机…', 45);
    try {
      await dbPut(state.formRecord);
      const index = state.records.findIndex(function (record) { return record.id === state.formRecord.id; });
      if (index >= 0) state.records[index] = deepClone(state.formRecord);
      else state.records.push(deepClone(state.formRecord));
      setProgress('保存完成', 100);
      setTimeout(hideProgress, 220);
      showToast(state.cloudService && state.cloudService.isConfigured() ? '已保存到本机，正在后台同步' : '检查记录已保存到本机');
      goBackFromForm();
      if (savedRecordId && state.cloudService && state.cloudService.isConfigured()) setTimeout(function () { runCloudSync(false); }, 120);
    } catch (error) {
      console.error(error);
      hideProgress();
      showToast('保存失败，可能是手机存储空间不足');
    }
  }

  function validateRecord(record) {
    if (!record.inspectionTypeId) return '请选择检查类型';
    if (!record.date) return '请选择检查时间';
    if (!record.location) return '请填写检查地点';
    const unanswered = record.items.findIndex(function (item) { return item.result !== 'yes' && item.result !== 'no' && item.result !== 'na'; });
    if (unanswered >= 0) return '请完成第 ' + (unanswered + 1) + ' 项检查结果';
    const missingIssue = record.items.findIndex(function (item) { return item.result === 'no' && !String(item.issue || '').trim(); });
    if (missingIssue >= 0) return '请填写第 ' + (missingIssue + 1) + ' 项现场问题';
    if (!record.inspectionPhotos.length) return '请至少上传一张检查照片';
    if (record.rectification.completed && !record.rectification.photos.length) return '勾选已整改完成前，请先补录整改照片';
    return '';
  }

  function getMissingSignatureLabels(record) {
    const signatures = record && record.signatures ? record.signatures : {};
    const labels = { inspector1: '检查人签名1', inspector2: '检查人签名2', inspected: '被检查人签名' };
    return Object.keys(labels).filter(function (key) { return !signatures[key]; }).map(function (key) { return labels[key]; });
  }

  async function deleteCurrentRecord() {
    if (!state.formRecord || !state.formRecord.id) return;
    if (state.cloudService && state.cloudService.isConfigured() && state.formRecord.cloudTracked) {
      el.deleteChoiceMessage.textContent = formatDateZh(state.formRecord.date) + '的检查记录将如何处理？';
      el.deleteReleaseLocalBtn.disabled = !(state.formRecord.cloudStatus === 'archived' && state.formRecord.archiveBlobId);
      el.deleteChoiceDialog.classList.remove('hidden');
      return;
    }
    const confirmed = await showConfirm('删除本机检查记录', '这条记录尚未进入云端，删除后本机照片和签名无法恢复。', '确认删除');
    if (!confirmed) return;
    try {
      await dbDelete(state.formRecord.id);
      state.records = state.records.filter(function (record) { return record.id !== state.formRecord.id; });
      state.selectedIds.delete(state.formRecord.id);
      showToast('检查记录已删除');
      goBackFromForm();
    } catch (error) {
      showToast('删除失败，请重试');
    }
  }

  function goBackFromForm() {
    if (location.hash === '#form') history.back();
    else showHome();
  }

  function onPopState() {
    if (state.signatureOpen) {
      closeSignature();
      return;
    }
    if (el.formView.classList.contains('active')) showHome();
  }

  function showHome() {
    state.formRecord = null;
    state.editingId = null;
    showView('home');
    renderHome();
    window.scrollTo(0, 0);
  }

  function showView(name) {
    el.homeView.classList.toggle('active', name === 'home');
    el.formView.classList.toggle('active', name === 'form');
  }

  function getCloudPlatform() {
    return window.android ? 'android' : 'windows';
  }

  function getDefaultDeviceName() {
    return getCloudPlatform() === 'android' ? '安卓手机' : 'Windows电脑';
  }

  function renderCloudUi() {
    if (!state.cloudService) {
      el.cloudStatusPill.className = 'cloud-status-pill error';
      el.cloudStatusPill.textContent = '组件缺失';
      el.cloudSyncDescription.textContent = '云同步组件未加载，本机填报和导出仍可继续使用。';
      el.cloudSetupBtn.disabled = true;
      return;
    }
    const summary = state.cloudService.getSummary();
    const configured = summary.configured;
    el.cloudFailureBanner.classList.toggle('hidden', !state.cloudFailureMessage);
    el.cloudFailureMessage.textContent = state.cloudFailureMessage || '';
    el.cloudUnitSummary.classList.toggle('hidden', !configured);
    [el.cloudSyncNowBtn, el.cloudDevicesBtn, el.cloudRecycleBtn, el.holidayUpdateBtn].forEach(function (button) {
      button.classList.toggle('hidden', !configured);
    });
    el.cloudSetupBtn.textContent = configured ? '同步设置' : '设置云同步';
    if (!configured) {
      el.cloudStatusPill.className = 'cloud-status-pill offline';
      el.cloudStatusPill.textContent = '未设置';
      el.cloudSyncDescription.textContent = '本机保存优先；可选择云服务提供商，在安卓手机与 Windows 电脑之间同步记录。';
      return;
    }
    el.cloudUnitName.textContent = summary.workspaceName || summary.teamName || '安全检查台账';
    el.cloudProviderSummary.textContent = '服务提供商：' + (summary.providerName || '兼容同步服务');
    el.cloudLastSync.textContent = summary.lastSyncAt ? '上次同步：' + formatCloudTime(summary.lastSyncAt) : '尚未完成首次同步';
    el.cloudSyncDescription.textContent = '已通过' + (summary.providerName || '云服务') + '连接 ' + Math.max(1, summary.activeDeviceCount || 1) + ' 台设备；联网后自动补传和恢复。';
    if (state.cloudSyncRunning) {
      el.cloudStatusPill.className = 'cloud-status-pill syncing';
      el.cloudStatusPill.textContent = '同步中';
    } else if (summary.lastError) {
      el.cloudStatusPill.className = 'cloud-status-pill error';
      el.cloudStatusPill.textContent = '等待重试';
      el.cloudLastSync.textContent = '最近失败：' + summary.lastError;
    } else {
      el.cloudStatusPill.className = 'cloud-status-pill online';
      el.cloudStatusPill.textContent = '已启用';
    }
  }

  function openCloudSetup() {
    if (!state.cloudService) return;
    const summary = state.cloudService.getSummary();
    const configured = summary.configured;
    populateCloudProviders(configured ? summary.providerId : (el.cloudProviderSelect.value || 'cloudflare'));
    el.cloudEndpoint.value = configured ? summary.endpoint : (el.cloudEndpoint.value || '');
    el.cloudTeamName.value = configured ? (summary.workspaceName || summary.teamName) : (el.cloudTeamName.value || '');
    el.cloudPassword.value = '';
    el.cloudDeviceName.value = configured ? summary.deviceName : getDefaultDeviceName();
    el.cloudExistingSummary.classList.toggle('hidden', !configured);
    el.cloudDisconnectBtn.classList.toggle('hidden', !configured);
    el.cloudSetupSubmitBtn.textContent = configured ? '保存更改并重新连接' : '保存并启用同步';
    [el.cloudProviderSelect, el.cloudEndpoint, el.cloudTeamName, el.cloudPassword, el.cloudDeviceName].forEach(function (field) { field.disabled = false; });
    if (configured) {
      el.cloudExistingSummary.textContent = '当前使用“' + summary.providerName + '”，同步空间为“' + (summary.workspaceName || summary.teamName) + '”，本设备为“' + summary.deviceName + '”。修改服务提供商后保存即可切换；旧服务中的数据不会自动删除。';
    }
    onCloudProviderChanged();
    resetCloudConnectionTest();
    el.cloudSetupDialog.classList.remove('hidden');
  }

  function closeCloudSetup() {
    el.cloudSetupDialog.classList.add('hidden');
  }

  function populateCloudProviders(selectedId) {
    const providers = state.cloudService && state.cloudService.getProviders ? state.cloudService.getProviders() : [];
    el.cloudProviderSelect.innerHTML = providers.map(function (provider) {
      return '<option value="' + escapeHtml(provider.id) + '">' + escapeHtml(provider.name) + '</option>';
    }).join('');
    el.cloudProviderSelect.value = providers.some(function (provider) { return provider.id === selectedId; }) ? selectedId : 'cloudflare';
  }

  function getSelectedCloudProvider() {
    const providers = state.cloudService && state.cloudService.getProviders ? state.cloudService.getProviders() : [];
    return providers.find(function (provider) { return provider.id === el.cloudProviderSelect.value; }) || providers[0] || { id: 'compatible', name: '兼容同步服务', endpointPlaceholder: 'https://你的同步服务地址', help: '填写兼容同步服务地址' };
  }

  function onCloudProviderChanged() {
    const provider = getSelectedCloudProvider();
    el.cloudProviderHelp.textContent = '当前选择：' + provider.name;
    el.cloudEndpoint.placeholder = provider.endpointPlaceholder || 'https://你的同步服务地址';
    el.cloudEndpointHelp.textContent = provider.help || '填写同步服务地址';
    resetCloudConnectionTest();
  }

  function resetCloudConnectionTest() {
    el.cloudTestResult.className = 'cloud-test-result idle';
    el.cloudTestResult.textContent = '尚未测试';
  }

  function setCloudConnectionTest(status, message) {
    el.cloudTestResult.className = 'cloud-test-result ' + status;
    el.cloudTestResult.textContent = message;
  }

  async function testCloudConnection() {
    if (!state.cloudService) return;
    prepareCloudFailureNotifications();
    const provider = getSelectedCloudProvider();
    setCloudConnectionTest('testing', '正在测试连接…');
    el.cloudTestBtn.disabled = true;
    try {
      const result = await state.cloudService.testConnection({ providerId: provider.id, endpoint: el.cloudEndpoint.value });
      setCloudConnectionTest('success', '连接成功 · 协议 v' + result.version);
      clearCloudFailure();
      showToast(provider.name + '连接测试成功');
    } catch (error) {
      const message = String(error && error.message || error);
      setCloudConnectionTest('failure', '测试失败：' + message);
      showCloudFailure('连接测试失败：' + message, true);
    } finally {
      el.cloudTestBtn.disabled = false;
    }
  }

  function prepareCloudFailureNotifications() {
    try {
      if (window.android) {
        if (typeof window.android.requestPermission === 'function') window.android.requestPermission('POST_NOTIFICATIONS');
        if (typeof window.android.newNotificationChannel === 'function') window.android.newNotificationChannel('云同步失败提醒', 'banner sound vibrate lockscreen');
      } else if (typeof Notification !== 'undefined' && Notification.permission === 'default' && typeof Notification.requestPermission === 'function') {
        Notification.requestPermission();
      }
    } catch (_) {}
  }

  function showCloudFailure(message, forceNotification) {
    const text = String(message || '请检查网络或同步设置');
    state.cloudFailureMessage = text;
    renderCloudUi();
    const now = Date.now();
    const canNotify = forceNotification || state.cloudLastFailureNotificationMessage !== text || now - state.cloudLastFailureNotificationAt > 30 * 60 * 1000;
    if (!canNotify) return;
    state.cloudLastFailureNotificationMessage = text;
    state.cloudLastFailureNotificationAt = now;
    try {
      if (window.android && typeof window.android.showNotification === 'function') {
        if (typeof window.android.newNotificationChannel === 'function') window.android.newNotificationChannel('云同步失败提醒', 'banner sound vibrate lockscreen');
        window.android.showNotification('云同步失败提醒', '安全检查台账', text);
      } else if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
        new Notification('安全检查台账', { body: text });
      }
    } catch (_) {}
  }

  function clearCloudFailure() {
    state.cloudFailureMessage = '';
    if (el.cloudFailureBanner) el.cloudFailureBanner.classList.add('hidden');
  }

  async function submitCloudSetup() {
    if (!state.cloudService) return;
    const password = el.cloudPassword.value;
    if (password.length < 8) {
      showToast('同步密码请至少填写8位');
      return;
    }
    if (!el.cloudTeamName.value.trim()) {
      showToast('请填写同步空间名称');
      return;
    }
    const provider = getSelectedCloudProvider();
    const oldSummary = state.cloudService.getSummary();
    if (oldSummary.configured) {
      const confirmed = await showConfirm('切换或重新连接云同步', '本机记录会同步到当前填写的服务。原云服务中的数据不会自动删除；请确认服务提供商、同步空间名称和密码填写正确。', '确认保存');
      if (!confirmed) return;
    }
    prepareCloudFailureNotifications();
    showProgress('正在连接云同步', '正在测试' + provider.name + '并连接同步空间…', 18);
    try {
      const input = {
        providerId: provider.id,
        endpoint: el.cloudEndpoint.value,
        workspaceName: el.cloudTeamName.value,
        password: password,
        deviceName: el.cloudDeviceName.value || getDefaultDeviceName(),
        platform: getCloudPlatform()
      };
      await state.cloudService.connectProvider(input);
      setProgress('正在准备本机记录同步…', 72);
      for (let index = 0; index < state.records.length; index++) {
        const record = state.records[index];
        if (record.archiveOnly || record.cloudStatus === 'trash') continue;
        record.cloudVersion = 0;
        record.cloudTracked = false;
        record.cloudStatus = 'active';
        record.syncPending = true;
        record.archiveBlobId = '';
        record.archivePageStart = 0;
        record.archivePageCount = 0;
        await dbPut(record);
      }
      state.cloudService.markSettingsPending();
      closeCloudSetup();
      renderHome();
      renderCloudUi();
      clearCloudFailure();
      setProgress('云同步已启用', 100);
      setTimeout(hideProgress, 260);
      showToast('已连接' + provider.name + '，正在同步本机记录');
      scheduleCloudAutoSync();
      setTimeout(function () { runCloudSync(true); }, 320);
    } catch (error) {
      console.error(error);
      hideProgress();
      const message = '云同步设置失败：' + String(error && error.message || error);
      showToast(message);
      showCloudFailure(message, true);
    }
  }

  async function disconnectCloudSync() {
    const confirmed = await showConfirm('断开本设备云同步', '只断开当前设备，不会删除云端、其他手机或电脑中的记录。本机已有完整记录仍会保留。', '确认断开');
    if (!confirmed) return;
    try {
      for (let index = 0; index < state.records.length; index++) {
        const record = state.records[index];
        record.cloudVersion = 0;
        record.cloudTracked = false;
        record.cloudStatus = record.cloudStatus === 'trash' ? 'active' : record.cloudStatus;
        record.syncPending = false;
        record.sourceDeviceId = '';
        record.deletedAt = '';
        await dbPut(record);
      }
      state.cloudService.disconnect();
      clearCloudFailure();
      closeCloudSetup();
      renderHome();
      renderCloudUi();
      showToast('当前设备已断开云同步，本机记录已保留');
    } catch (error) {
      showToast('断开失败：' + String(error && error.message || error));
    }
  }

  function scheduleCloudAutoSync() {
    if (state.cloudAutoTimer || !state.cloudService || !state.cloudService.isConfigured()) return;
    state.cloudAutoTimer = setTimeout(function tick() {
      state.cloudAutoTimer = null;
      if (state.cloudService && state.cloudService.isConfigured()) {
        if (!document.hidden) runCloudSync(false);
        scheduleCloudAutoSync();
      }
    }, 90000);
  }

  async function runCloudSync(manual) {
    if (!state.cloudService || !state.cloudService.isConfigured() || state.cloudSyncRunning) return;
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      if (manual) showToast('当前没有网络，记录已保存在本机，联网后会自动同步');
      return;
    }
    state.cloudSyncRunning = true;
    renderCloudUi();
    if (manual) showProgress('正在同步', '正在连接云端目录…', 3);
    try {
      const summary = await state.cloudService.sync({
        records: state.records,
        inspectionTypes: state.inspectionTypes,
        callbacks: {
          progress: function (message, percent) {
            if (manual) setProgress(message, percent);
          },
          upsertRecord: upsertCloudRecord,
          removeRecord: removeCloudRecord,
          applyInspectionTypes: applyCloudInspectionTypes
        }
      });
      clearCloudFailure();
      renderHome();
      if (manual) {
        setProgress('同步完成', 100);
        setTimeout(hideProgress, 260);
        showToast('同步完成：上传' + summary.uploaded + '条，下载' + summary.downloaded + '条' + (summary.conflicts ? '，保留冲突副本' + summary.conflicts + '条' : ''));
      }
      setTimeout(runCloudArchiveMaintenance, 900);
    } catch (error) {
      console.error(error);
      const failureMessage = '云同步失败：' + String(error && error.message || error) + '；本机记录已保留';
      showCloudFailure(failureMessage, false);
      if (manual) {
        hideProgress();
        showToast(failureMessage);
      }
    } finally {
      state.cloudSyncRunning = false;
      renderCloudUi();
    }
  }

  async function upsertCloudRecord(record) {
    normalizeRecord(record);
    await dbPut(record);
    const index = state.records.findIndex(function (item) { return item.id === record.id; });
    if (index >= 0) state.records[index] = deepClone(record);
    else state.records.push(deepClone(record));
  }

  async function removeCloudRecord(id) {
    await dbDelete(id);
    state.records = state.records.filter(function (record) { return record.id !== id; });
    state.selectedIds.delete(id);
  }

  function applyCloudInspectionTypes(remoteTypes, mergePending) {
    const sanitized = sanitizeInspectionTypes(remoteTypes || []);
    if (!sanitized.length) return state.inspectionTypes;
    state.inspectionTypes = mergePending ? mergeInspectionTypes(state.inspectionTypes, sanitized) : sanitized;
    persistInspectionTypes(state.inspectionTypes);
    return state.inspectionTypes;
  }

  async function refreshHolidayYears(years, manual) {
    if (!state.cloudService || !state.cloudService.isConfigured()) {
      if (manual) showToast('请先设置云同步服务，再联网更新节假日');
      return;
    }
    const uniqueYears = Array.from(new Set((years || []).map(Number).filter(Boolean)));
    let updated = 0;
    for (let index = 0; index < uniqueYears.length; index++) {
      const year = uniqueYears[index];
      const cached = state.onlineHolidays && state.onlineHolidays[String(year)];
      if (!manual && cached && Date.now() - Date.parse(cached.fetchedAt || '') < 24 * 60 * 60 * 1000) continue;
      if (state.holidayRefreshYears.has(year)) continue;
      state.holidayRefreshYears.add(year);
      try {
        await state.cloudService.fetchHolidayYear(year);
        updated++;
      } catch (error) {
        if (manual) showToast(year + '年法定节假日暂未发布或网络不可用');
      } finally {
        state.holidayRefreshYears.delete(year);
      }
    }
    state.onlineHolidays = state.cloudService.getCachedHolidays();
    renderCalendar();
    if (manual && updated) showToast('法定节假日数据已更新并保存到本机');
  }

  async function openCloudDevices() {
    if (!state.cloudService || !state.cloudService.isConfigured()) return;
    el.cloudDevicesDialog.classList.remove('hidden');
    el.cloudDevicesList.innerHTML = '<p class="cloud-list-empty">正在读取已连接设备…</p>';
    try {
      const result = await state.cloudService.getDevices();
      if (!result.devices.length) {
        el.cloudDevicesList.innerHTML = '<p class="cloud-list-empty">暂无设备</p>';
        return;
      }
      const isAdmin = state.cloudService.getSummary().role === 'admin';
      el.cloudDevicesList.innerHTML = result.devices.map(function (device) {
        const current = device.deviceId === result.currentDeviceId;
        return '<div class="cloud-device-row" data-device-id="' + escapeHtml(device.deviceId) + '"><div><strong>' + escapeHtml(device.name) + (current ? '（本设备）' : '') + '</strong><span>' + (device.platform === 'windows' ? 'Windows电脑' : '安卓手机') + ' · ' + (device.role === 'admin' ? '管理员' : '成员') + ' · 最近在线 ' + escapeHtml(formatCloudTime(device.lastSeen)) + '</span></div><div class="cloud-row-actions">' + (isAdmin && !current && device.active ? '<button class="danger" data-action="revoke-device" type="button">移除设备</button>' : '') + (device.active ? '' : '<span>已移除</span>') + '</div></div>';
      }).join('');
    } catch (error) {
      el.cloudDevicesList.innerHTML = '<p class="cloud-list-empty">读取失败：' + escapeHtml(error.message || error) + '</p>';
    }
  }

  async function onCloudDeviceListClick(event) {
    const button = event.target.closest('[data-action="revoke-device"]');
    if (!button) return;
    const row = button.closest('[data-device-id]');
    const confirmed = await showConfirm('移除设备', '该设备之后不能再同步；设备本机已有数据不会被远程擦除。', '确认移除');
    if (!confirmed) return;
    try {
      await state.cloudService.revokeDevice(row.dataset.deviceId);
      showToast('设备已移除');
      openCloudDevices();
    } catch (error) {
      showToast('移除失败：' + String(error.message || error));
    }
  }

  function openCloudRecycle() {
    el.cloudRecycleDialog.classList.remove('hidden');
    renderCloudRecycle();
  }

  function renderCloudRecycle() {
    const records = state.records.filter(function (record) { return record.cloudStatus === 'trash'; }).sort(function (a, b) { return String(b.deletedAt).localeCompare(String(a.deletedAt)); });
    if (!records.length) {
      el.cloudRecycleList.innerHTML = '<p class="cloud-list-empty">回收站为空</p>';
      return;
    }
    const isAdmin = state.cloudService && state.cloudService.getSummary().role === 'admin';
    el.cloudRecycleList.innerHTML = records.map(function (record) {
      return '<div class="cloud-trash-row" data-record-id="' + escapeHtml(record.id) + '"><div><strong>' + escapeHtml(formatDateZh(record.date)) + ' · ' + escapeHtml(getRecordTypeName(record)) + '</strong><span>移入回收站：' + escapeHtml(formatCloudTime(record.deletedAt)) + '</span></div><div class="cloud-row-actions"><button data-action="restore-record" type="button">恢复</button>' + (isAdmin ? '<button class="danger" data-action="hard-delete-record" type="button">彻底删除</button>' : '') + '</div></div>';
    }).join('');
  }

  async function onCloudRecycleListClick(event) {
    const button = event.target.closest('[data-action]');
    if (!button) return;
    const row = button.closest('[data-record-id]');
    const record = state.records.find(function (item) { return item.id === row.dataset.recordId; });
    if (!record) return;
    if (button.dataset.action === 'restore-record') {
      try {
        const restored = await state.cloudService.restoreRecord(record);
        await upsertCloudRecord(restored);
        renderCloudRecycle();
        renderHome();
        showToast('记录已恢复，正在补齐云端内容');
        setTimeout(function () { runCloudSync(false); }, 80);
      } catch (error) {
        showToast('恢复失败：' + String(error.message || error));
      }
      return;
    }
    state.cloudRecycleTargetId = record.id;
    el.hardDeletePassword.value = '';
    el.hardDeleteDialog.classList.remove('hidden');
  }

  function closeHardDeleteDialog() {
    state.cloudRecycleTargetId = '';
    el.hardDeletePassword.value = '';
    el.hardDeleteDialog.classList.add('hidden');
  }

  async function confirmHardDeleteRecord() {
    if (!state.cloudRecycleTargetId || !el.hardDeletePassword.value) {
      showToast('请输入单位同步密码');
      return;
    }
    try {
      await state.cloudService.hardDeleteRecord(state.cloudRecycleTargetId, el.hardDeletePassword.value);
      await removeCloudRecord(state.cloudRecycleTargetId);
      closeHardDeleteDialog();
      renderCloudRecycle();
      renderHome();
      showToast('已彻底删除云端及所有同步设备上的记录');
    } catch (error) {
      showToast('彻底删除失败：' + String(error.message || error));
    }
  }

  function closeDeleteChoice() {
    el.deleteChoiceDialog.classList.add('hidden');
  }

  async function releaseCurrentRecordLocal() {
    if (!state.formRecord) return;
    try {
      const placeholder = state.cloudService.releaseLocalRecord(state.formRecord);
      await upsertCloudRecord(placeholder);
      closeDeleteChoice();
      showToast('本机照片和可编辑正文已释放，云端PDF仍可导出');
      goBackFromForm();
    } catch (error) {
      showToast(String(error.message || error));
    }
  }

  async function moveCurrentRecordToTrash() {
    if (!state.formRecord) return;
    showProgress('正在移入回收站', '正在同步到其他设备…', 35);
    try {
      const trashed = await state.cloudService.trashRecord(state.formRecord);
      await upsertCloudRecord(trashed);
      closeDeleteChoice();
      setProgress('已移入回收站', 100);
      setTimeout(hideProgress, 220);
      showToast('记录已移入所有设备回收站，可在首页恢复');
      goBackFromForm();
    } catch (error) {
      hideProgress();
      showToast('移入回收站失败：' + String(error.message || error));
    }
  }

  async function downloadArchivedRecordPdf(record) {
    if (!state.cloudService || !state.cloudService.isConfigured() || !record.archiveBlobId) {
      showToast('云端PDF目前不可读取');
      return;
    }
    showProgress('正在读取云端归档', formatDateZh(record.date) + '，请稍候…', 30);
    try {
      const sourceResult = await state.cloudService.getExportSources([record], function (message, percent) {
        setProgress(message, 30 + Math.round(percent * 0.45));
      });
      if (sourceResult.missingDates.length || !sourceResult.sources.length) throw new Error('云端归档页码或文件缺失');
      const pdfDocument = await createCombinedPdfFromSources(sourceResult.sources, null);
      setProgress('正在保存PDF…', 85);
      await savePdfFile(formatDateZh(record.date) + '.pdf', await pdfDocument.saveAsBase64({ dataUri: false }));
      setProgress('PDF已保存', 100);
      setTimeout(hideProgress, 240);
      showToast('云端归档PDF已保存');
    } catch (error) {
      hideProgress();
      showToast('读取云端归档失败：' + String(error.message || error));
    }
  }

  async function runCloudArchiveMaintenance() {
    if (state.cloudArchiveRunning || state.cloudSyncRunning || !state.cloudService || !state.cloudService.isConfigured()) return;
    const cutoff = new Date();
    cutoff.setMonth(cutoff.getMonth() - 6);
    const eligible = state.records.filter(function (record) {
      if (record.archiveOnly || record.cloudStatus !== 'active' || !record.cloudTracked || record.syncPending) return false;
      if (parseLocalDate(record.date) >= cutoff) return false;
      if (hasIssues(record) && !(record.rectification && record.rectification.completed)) return false;
      return true;
    }).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)) || String(a.createdAt || '').localeCompare(String(b.createdAt || '')); });
    if (!eligible.length) return;
    const first = eligible[0];
    const monthKey = String(first.date || '').slice(0, 7);
    const typeId = first.inspectionTypeId;
    const candidates = eligible.filter(function (record) {
      return String(record.date || '').slice(0, 7) === monthKey && record.inspectionTypeId === typeId;
    });
    let batch = [];
    let photoCount = 0;
    for (let index = 0; index < candidates.length; index++) {
      const nextPhotos = getRecordPhotos(candidates[index]).length;
      if (batch.length && (batch.length >= 8 || photoCount + nextPhotos > 24)) break;
      batch.push(candidates[index]);
      photoCount += nextPhotos;
    }
    state.cloudArchiveRunning = true;
    try {
      const fontBytes = await loadPdfFont();
      let pdfDocument = await createCombinedPdf(batch, fontBytes);
      let pdfBytes = await pdfDocument.save();
      while (pdfBytes.length > 30 * 1024 * 1024 && batch.length > 1) {
        batch = batch.slice(0, Math.ceil(batch.length / 2));
        pdfDocument = await createCombinedPdf(batch, fontBytes);
        pdfBytes = await pdfDocument.save();
      }
      if (pdfBytes.length > 30 * 1024 * 1024) throw new Error('单条记录PDF超过免费云端归档上限');
      let pageStart = 0;
      const ranges = batch.map(function (record) {
        const pageCountForRecord = 1 + Math.ceil(getRecordPhotos(record).length / 4);
        const range = { recordId: record.id, pageStart: pageStart, pageCount: pageCountForRecord };
        pageStart += pageCountForRecord;
        return range;
      });
      const batchId = monthKey + '-' + typeId + '-' + Date.now().toString(36);
      const archivedRecords = await state.cloudService.archiveRecords(batch, pdfBytes, ranges, batchId);
      for (let index = 0; index < archivedRecords.length; index++) await upsertCloudRecord(archivedRecords[index]);
      renderHome();
    } catch (error) {
      console.warn('云端PDF归档稍后重试', error);
    } finally {
      state.cloudArchiveRunning = false;
    }
  }

  function formatCloudTime(value) {
    const date = new Date(value || '');
    if (Number.isNaN(date.getTime())) return value || '—';
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) + ' ' + pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  }

  async function exportSyncFile() {
    if (!state.records.length) {
      showToast('还没有可同步的检查记录');
      return;
    }
    const confirmed = await showConfirm(
      '导出同步文件',
      '将导出全部 ' + state.records.length + ' 条检查记录，包含检查照片、整改照片和已有签名。可复制到 Windows 或另一部手机导入。',
      '开始导出'
    );
    if (!confirmed) return;
    showProgress('正在导出同步文件', '正在整理本机记录和照片…', 30);
    try {
      const payload = {
        schema: SYNC_SCHEMA,
        version: 4,
        appVersion: APP_VERSION,
        exportedAt: new Date().toISOString(),
        recordCount: state.records.length,
        inspectionTypes: state.inspectionTypes,
        records: state.records
      };
      const bytes = utf8ToUint8Array(JSON.stringify(payload));
      setProgress('正在写入下载文件夹…', 72);
      await saveBinaryFile('安全检查台账同步-' + todayValue() + '.csinspect', 'application/x-safety-inspection-ledger', uint8ArrayToBase64(bytes));
      setProgress('同步文件已导出', 100);
      setTimeout(hideProgress, 260);
      showToast('同步文件已保存，可复制到 Windows 导入');
    } catch (error) {
      console.error(error);
      hideProgress();
      showToast('同步文件导出失败：' + String(error && error.message ? error.message : error));
    }
  }

  async function importSyncFile(event) {
    const input = event && event.target ? event.target : el.syncFileInput;
    const file = input.files && input.files[0];
    if (!file) return;
    try {
      const rawText = await readFileText(file);
      const payload = JSON.parse(rawText);
      if (!payload || payload.schema !== SYNC_SCHEMA || !Array.isArray(payload.records)) {
        throw new Error('这不是本软件导出的同步文件');
      }
      const importedRecords = prepareImportedRecords(payload.records);
      if (!importedRecords.length) throw new Error('同步文件中没有有效检查记录');
      const preview = previewSyncMerge(importedRecords);
      const confirmed = await showConfirm(
        '导入同步文件',
        '文件内有 ' + importedRecords.length + ' 条记录：预计新增 ' + preview.added + ' 条、更新 ' + preview.updated + ' 条，本机相同或较新的 ' + preview.kept + ' 条会保留。是否继续？',
        '确认导入'
      );
      if (!confirmed) return;
      showProgress('正在导入同步文件', '正在写入检查记录和照片…', 10);
      const importedTypes = sanitizeInspectionTypes(payload.inspectionTypes || []);
      if (importedTypes.length) {
        state.inspectionTypes = mergeInspectionTypes(state.inspectionTypes, importedTypes);
        persistInspectionTypes(state.inspectionTypes);
        if (state.cloudService && state.cloudService.isConfigured()) state.cloudService.markSettingsPending();
      }
      let applied = 0;
      for (let index = 0; index < importedRecords.length; index++) {
        const imported = importedRecords[index];
        if (state.cloudService && state.cloudService.isConfigured() && !imported.archiveOnly) {
          imported.cloudVersion = 0;
          imported.cloudTracked = false;
          imported.cloudStatus = 'active';
          imported.syncPending = true;
          imported.archiveBlobId = '';
          imported.archivePageStart = 0;
          imported.archivePageCount = 0;
        }
        const local = state.records.find(function (record) { return record.id === imported.id; });
        if (!local || isImportedRecordNewer(imported, local)) {
          await dbPut(imported);
          applied++;
        }
        setProgress('正在导入第 ' + (index + 1) + '/' + importedRecords.length + ' 条…', 10 + Math.round(((index + 1) / importedRecords.length) * 84));
      }
      state.records = await dbGetAll();
      state.records.forEach(normalizeRecord);
      state.selectedIds.clear();
      state.recordFilter.mode = 'all';
      state.recordFilter.typeId = 'all';
      renderHome();
      setProgress('导入完成', 100);
      setTimeout(hideProgress, 260);
      showToast('导入完成，已新增或更新 ' + applied + ' 条记录');
      if (state.cloudService && state.cloudService.isConfigured()) setTimeout(function () { runCloudSync(false); }, 120);
    } catch (error) {
      console.error(error);
      hideProgress();
      showToast('导入失败：' + String(error && error.message ? error.message : error));
    } finally {
      input.value = '';
    }
  }

  function prepareImportedRecords(records) {
    const byId = new Map();
    records.forEach(function (rawRecord) {
      if (!rawRecord || typeof rawRecord !== 'object' || !rawRecord.date || !Array.isArray(rawRecord.items)) return;
      const record = deepClone(rawRecord);
      if (!record.id) record.id = makeId('record');
      normalizeRecord(record);
      const previous = byId.get(record.id);
      if (!previous || isImportedRecordNewer(record, previous)) byId.set(record.id, record);
    });
    return Array.from(byId.values());
  }

  function mergeInspectionTypes(localTypes, importedTypes) {
    const merged = deepClone(localTypes || []);
    importedTypes.forEach(function (imported) {
      const index = merged.findIndex(function (local) { return local.id === imported.id; });
      if (index >= 0) merged[index] = { id: imported.id, name: imported.name, items: sanitizeTemplateItems(imported.items) };
      else if (!merged.some(function (local) { return local.name === imported.name; })) merged.push(imported);
    });
    return sanitizeInspectionTypes(merged);
  }

  function previewSyncMerge(importedRecords) {
    let added = 0;
    let updated = 0;
    let kept = 0;
    importedRecords.forEach(function (imported) {
      const local = state.records.find(function (record) { return record.id === imported.id; });
      if (!local) added++;
      else if (isImportedRecordNewer(imported, local)) updated++;
      else kept++;
    });
    return { added: added, updated: updated, kept: kept };
  }

  function isImportedRecordNewer(imported, local) {
    const importedTime = Date.parse(imported.updatedAt || imported.createdAt || '') || 0;
    const localTime = Date.parse(local.updatedAt || local.createdAt || '') || 0;
    return importedTime > localTime;
  }

  function readFileText(file) {
    if (file && typeof file.text === 'function') return file.text();
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result || '')); };
      reader.onerror = function () { reject(reader.error || new Error('文件读取失败')); };
      reader.readAsText(file, 'utf-8');
    });
  }

  function openExportChoice() {
    const count = state.selectedIds.size;
    if (!count) {
      showToast('请先选择要导出的检查记录');
      return;
    }
    if (state.recordFilter.mode === 'year' || state.recordFilter.mode === 'quarter') {
      exportSelectedRecords('combined');
      return;
    }
    el.exportChoiceMessage.textContent = '已选择 ' + count + ' 条（' + getFilterLabel() + '）。请选择生成一个完整 PDF，或逐条生成多个 PDF。';
    el.exportChoiceDialog.classList.remove('hidden');
  }

  function closeExportChoice() {
    el.exportChoiceDialog.classList.add('hidden');
  }

  async function exportSelectedRecords(mode) {
    const records = state.records.filter(function (record) { return state.selectedIds.has(record.id); }).sort(function (a, b) {
      return String(a.date || '').localeCompare(String(b.date || '')) || String(a.createdAt || '').localeCompare(String(b.createdAt || ''));
    });
    if (!records.length) return;
    showProgress('正在生成 PDF', '正在核对本机与云端检查目录…', 2);
    let successCount = 0;
    const failedDates = [];
    const failedReasons = [];
    try {
      let sourceResult = { sources: records.map(function (record) { return { date: record.date, id: record.id, kind: 'record', record: record }; }), missingDates: [] };
      if (state.cloudService && state.cloudService.isConfigured()) {
        const filtered = getFilteredRecords();
        const selectedAllFiltered = filtered.length > 0 && filtered.every(function (record) { return state.selectedIds.has(record.id); });
        try {
          sourceResult = await state.cloudService.getExportSources(records, function (message, percent) {
            setProgress(message, 4 + Math.round(percent * 0.28));
          }, Object.assign({}, state.recordFilter, { includeAllFiltered: selectedAllFiltered }));
        } catch (cloudError) {
          if (records.some(function (record) { return record.archiveOnly; })) throw cloudError;
          showToast('云端暂时不可连接，将使用本机完整记录导出');
        }
      }
      if (sourceResult.missingDates && sourceResult.missingDates.length) {
        hideProgress();
        await showConfirm('无法完整导出', '以下检查日期的云端正文或PDF缺失：' + sourceResult.missingDates.map(formatDateZh).join('、') + '。本次不会生成不完整文件，请联网同步或恢复后重试。', '知道了');
        return;
      }
      const sources = sourceResult.sources || [];
      if (!sources.length) throw new Error('没有可导出的完整检查记录');
      const needsFont = sources.some(function (source) { return source.kind === 'record'; });
      const fontBytes = needsFont ? await loadPdfFont() : null;
      if (mode === 'combined') {
        setProgress('正在按检查日期合并 ' + sources.length + ' 条记录…', 36);
        const combinedDocument = await createCombinedPdfFromSources(sources, fontBytes);
        setProgress('正在写入完整 PDF…', 88);
        const combinedBase64 = await combinedDocument.saveAsBase64({ dataUri: false });
        await savePdfFile(getCombinedExportFileName(sources.map(function (source) { return source.record || { id: source.id, date: source.date }; })), combinedBase64);
        setProgress('完整 PDF 已生成', 100);
        setTimeout(hideProgress, 320);
        showToast('已将 ' + sources.length + ' 条检查记录按日期合并为一个 PDF');
        return;
      }
      const dateCounts = {};
      for (let index = 0; index < sources.length; index++) {
        const source = sources[index];
        setProgress('正在生成 ' + formatDateZh(source.date) + '（' + (index + 1) + '/' + sources.length + '）', Math.round((index / sources.length) * 62) + 32);
        try {
          let base64;
          if (source.kind === 'pdf') base64 = uint8ArrayToBase64(source.bytes);
          else {
            const pdfDocument = await createRecordPdf(source.record, fontBytes);
            base64 = await pdfDocument.saveAsBase64({ dataUri: false });
          }
          dateCounts[source.date] = (dateCounts[source.date] || 0) + 1;
          const suffix = dateCounts[source.date] > 1 ? '-' + dateCounts[source.date] : '';
          const fileName = formatDateZh(source.date) + suffix + '.pdf';
          await savePdfFile(fileName, base64);
          successCount++;
        } catch (error) {
          console.error(error);
          failedDates.push(formatDateZh(source.date));
          failedReasons.push(error && error.message ? error.message : String(error || '未知错误'));
        }
        await wait(120);
      }
      setProgress('PDF 已生成', 100);
      setTimeout(hideProgress, 320);
      if (failedDates.length) showToast('生成失败：' + friendlyPdfError(failedReasons[0]));
      else showToast('已逐条生成 ' + successCount + ' 个 PDF');
    } catch (error) {
      console.error(error);
      hideProgress();
      showToast('PDF 准备失败：' + friendlyPdfError(error && error.message));
    }
  }

  async function createCombinedPdfFromSources(sources, fontBytes) {
    if (!window.PDFLib) throw new Error('PDF 组件未加载');
    const PDFDocument = window.PDFLib.PDFDocument;
    const output = await PDFDocument.create();
    const ordered = sources.slice().sort(function (a, b) {
      return String(a.date || '').localeCompare(String(b.date || '')) || String(a.id || '').localeCompare(String(b.id || ''));
    });
    for (let index = 0; index < ordered.length; index++) {
      const source = ordered[index];
      let input;
      if (source.kind === 'pdf') input = await PDFDocument.load(source.bytes);
      else input = await createRecordPdf(source.record, fontBytes);
      let pageIndices = input.getPageIndices();
      if (source.kind === 'pdf' && Number(source.pageCount) > 0) {
        const start = Math.max(0, Number(source.pageStart) || 0);
        pageIndices = pageIndices.slice(start, start + Number(source.pageCount));
      }
      if (!pageIndices.length) throw new Error(formatDateZh(source.date) + '的云端PDF页码范围无效');
      const pages = await output.copyPages(input, pageIndices);
      pages.forEach(function (page) { output.addPage(page); });
    }
    output.setTitle('安全检查台账（' + ordered.length + '条）');
    output.setSubject('安全检查记录、现场照片及整改照片');
    output.setCreator('安全检查台账 v' + APP_VERSION);
    output.setProducer('安全检查台账 v' + APP_VERSION);
    output.setCreationDate(new Date());
    return output;
  }

  function getCombinedExportFileName(records) {
    const filter = state.recordFilter;
    let base = '安全检查台账';
    if (filter.mode === 'day') base = formatDateZh(filter.day) + '安全检查台账';
    else if (filter.mode === 'month') base = filter.year + '年' + pad2(filter.month) + '月安全检查台账';
    else if (filter.mode === 'quarter') base = filter.year + '年第' + filter.quarter + '季度安全检查台账';
    else if (filter.mode === 'year') base = filter.year + '年安全检查台账';
    else if (records.length !== state.records.length) base = '安全检查台账-' + records.length + '条记录';
    else base = '安全检查台账-全部记录';
    return base + '.pdf';
  }

  async function loadPdfFont() {
    if (state.fontBytes) return state.fontBytes;
    if (Array.isArray(window.__CAR_SHED_PDF_FONT_CHUNKS) && window.__CAR_SHED_PDF_FONT_CHUNKS.length) {
      state.fontBytes = base64ChunksToUint8Array(window.__CAR_SHED_PDF_FONT_CHUNKS);
      window.__CAR_SHED_PDF_FONT_CHUNKS = [];
      if (state.fontBytes.length < 1000000) throw new Error('中文字体数据不完整');
      return state.fontBytes;
    }
    try {
      const response = await fetch('fonts/NotoSansHans-Regular.woff');
      if (!response.ok) throw new Error('字体读取失败');
      state.fontBytes = new Uint8Array(await response.arrayBuffer());
      return state.fontBytes;
    } catch (_) {
      state.fontBytes = await loadArrayBufferWithXhr('fonts/NotoSansHans-Regular.woff');
      return state.fontBytes;
    }
  }

  function loadArrayBufferWithXhr(url) {
    return new Promise(function (resolve, reject) {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.responseType = 'arraybuffer';
      xhr.onload = function () { xhr.status === 200 || xhr.status === 0 ? resolve(new Uint8Array(xhr.response)) : reject(new Error('字体读取失败')); };
      xhr.onerror = function () { reject(new Error('字体读取失败')); };
      xhr.send();
    });
  }

  async function savePdfFile(fileName, base64) {
    return saveBinaryFile(fileName, 'application/pdf', base64);
  }

  async function saveBinaryFile(fileName, mimeType, base64) {
    try {
      if (window.android && typeof window.android.saveFile === 'function') {
        const result = window.android.saveFile(fileName, mimeType, base64);
        if (typeof result === 'string' && result.indexOf('OK') === 0) return;
        throw new Error(result || '保存失败');
      }
      if (window.android && typeof window.android.savePdf === 'function') {
        const result = window.android.savePdf(fileName, base64);
        if (typeof result === 'string' && result.indexOf('OK') === 0) return;
        throw new Error(result || '保存失败');
      }
    } catch (error) {
      if (window.android) throw error;
    }
    const bytes = base64ToUint8Array(base64);
    const blob = new Blob([bytes], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
  }

  function friendlyPdfError(message) {
    const text = String(message || '未知错误').replace(/^ERROR:/, '');
    if (text.indexOf('PDF 组件') >= 0) return 'PDF 组件加载失败，请重新打开软件';
    if (text.indexOf('字体') >= 0) return '中文字体加载失败';
    if (text.indexOf('创建PDF') >= 0 || text.indexOf('写入PDF') >= 0) return text;
    if (text.indexOf('memory') >= 0 || text.indexOf('Memory') >= 0) return '照片较多导致内存不足，请减少单次选择数量';
    return text;
  }

  function showProgress(title, message, percent) {
    el.progressTitle.textContent = title;
    el.progressMessage.textContent = message;
    el.progressFill.style.width = Math.max(0, Math.min(100, percent || 0)) + '%';
    el.progressOverlay.classList.remove('hidden');
  }

  function setProgress(message, percent) {
    el.progressMessage.textContent = message;
    el.progressFill.style.width = Math.max(0, Math.min(100, percent)) + '%';
  }

  function hideProgress() { el.progressOverlay.classList.add('hidden'); }

  function showToast(message) {
    clearTimeout(state.toastTimer);
    el.toast.textContent = message;
    el.toast.classList.remove('hidden');
    state.toastTimer = setTimeout(function () { el.toast.classList.add('hidden'); }, 3400);
  }

  function showConfirm(title, message, okText) {
    el.confirmTitle.textContent = title;
    el.confirmMessage.textContent = message;
    el.confirmOkBtn.textContent = okText || '确定';
    el.confirmDialog.classList.remove('hidden');
    return new Promise(function (resolve) { state.confirmResolve = resolve; });
  }

  function resolveConfirm(value) {
    el.confirmDialog.classList.add('hidden');
    if (state.confirmResolve) state.confirmResolve(value);
    state.confirmResolve = null;
  }

  function formatDateZh(value) {
    const date = parseLocalDate(value);
    if (Number.isNaN(date.getTime())) return value || '未填写日期';
    return date.getFullYear() + '年' + pad2(date.getMonth() + 1) + '月' + pad2(date.getDate()) + '日';
  }

  function formatDateTimeZh(date) {
    return date.getFullYear() + '年' + pad2(date.getMonth() + 1) + '月' + pad2(date.getDate()) + '日 ' + pad2(date.getHours()) + ':' + pad2(date.getMinutes()) + ':' + pad2(date.getSeconds());
  }

  function getQuarterLabel(value) {
    const date = parseLocalDate(value);
    if (Number.isNaN(date.getTime())) return '';
    return '第' + (Math.floor(date.getMonth() / 3) + 1) + '季度';
  }

  function parseLocalDate(value) {
    const parts = String(value || '').split('-').map(Number);
    return new Date(parts[0] || 0, (parts[1] || 1) - 1, parts[2] || 1);
  }

  function todayValue() {
    const date = new Date();
    return dateKey(date);
  }

  function dateKey(date) { return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()); }
  function pad2(value) { return String(value).padStart(2, '0'); }
  function makeId(prefix) { return prefix + '-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9); }
  function deepClone(value) { return JSON.parse(JSON.stringify(value)); }
  function wait(milliseconds) { return new Promise(function (resolve) { setTimeout(resolve, milliseconds); }); }
  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>'"]/g, function (char) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char];
    });
  }

  function base64ToUint8Array(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function utf8ToUint8Array(text) {
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(text);
    const encoded = unescape(encodeURIComponent(text));
    const bytes = new Uint8Array(encoded.length);
    for (let index = 0; index < encoded.length; index++) bytes[index] = encoded.charCodeAt(index);
    return bytes;
  }

  function uint8ArrayToBase64(bytes) {
    const parts = [];
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))));
    }
    return btoa(parts.join(''));
  }

  function base64ChunksToUint8Array(chunks) {
    const totalCharacters = chunks.reduce(function (total, chunk) { return total + chunk.length; }, 0);
    const lastChunk = chunks[chunks.length - 1] || '';
    let padding = 0;
    if (lastChunk.endsWith('==')) padding = 2;
    else if (lastChunk.endsWith('=')) padding = 1;
    const bytes = new Uint8Array(Math.floor(totalCharacters * 3 / 4) - padding);
    let offset = 0;
    chunks.forEach(function (chunk) {
      const binary = atob(chunk);
      for (let index = 0; index < binary.length; index++) bytes[offset++] = binary.charCodeAt(index);
    });
    return bytes;
  }

  // PDF generation helpers are defined below. They intentionally use only bundled assets.

  async function createRecordPdf(record, fontBytes) {
    return createPdfForRecords([record], fontBytes, false);
  }

  async function createCombinedPdf(records, fontBytes) {
    return createPdfForRecords(records, fontBytes, true);
  }

  async function createPdfForRecords(records, fontBytes, combined) {
    if (!window.PDFLib || !window.fontkit) throw new Error('PDF 组件未加载');
    const PDFDocument = window.PDFLib.PDFDocument;
    const pdfDocument = await PDFDocument.create();
    pdfDocument.registerFontkit(window.fontkit);
    const font = await pdfDocument.embedFont(fontBytes, { subset: false });
    const orderedRecords = records.slice().sort(function (a, b) {
      return String(a.date || '').localeCompare(String(b.date || '')) || String(a.createdAt || '').localeCompare(String(b.createdAt || ''));
    });
    const title = combined ? '安全检查台账（' + orderedRecords.length + '条）' : formatDateZh(orderedRecords[0].date) + getRecordTypeName(orderedRecords[0]);
    pdfDocument.setTitle(title);
    pdfDocument.setSubject('安全检查记录、现场照片及整改照片');
    pdfDocument.setCreator('安全检查台账 v' + APP_VERSION);
    pdfDocument.setProducer('安全检查台账 v' + APP_VERSION);
    pdfDocument.setCreationDate(new Date());

    const preparedRecords = orderedRecords.map(function (record) {
      const photos = getRecordPhotos(record);
      return { record: record, photos: photos, pageCount: 1 + Math.ceil(photos.length / 4) };
    });
    const pageSize = [595.28, 841.89];
    for (let recordIndex = 0; recordIndex < preparedRecords.length; recordIndex++) {
      const prepared = preparedRecords[recordIndex];
      const firstPage = pdfDocument.addPage(pageSize);
      await drawInspectionSheet(pdfDocument, firstPage, font, prepared.record, 1, prepared.pageCount);
      for (let pageIndex = 0; pageIndex < Math.ceil(prepared.photos.length / 4); pageIndex++) {
        const page = pdfDocument.addPage(pageSize);
        await drawPhotoAppendixPage(pdfDocument, page, font, prepared.record, prepared.photos.slice(pageIndex * 4, pageIndex * 4 + 4), pageIndex + 2, prepared.pageCount);
      }
    }
    return pdfDocument;
  }

  function getRecordPhotos(record) {
    const photos = [];
    (record.inspectionPhotos || []).forEach(function (photo, index) {
      photos.push({ data: photo.data, label: '检查照片 ' + (index + 1), kind: 'inspection' });
    });
    const rectificationPhotos = record.rectification && record.rectification.photos ? record.rectification.photos : [];
    rectificationPhotos.forEach(function (photo, index) {
      photos.push({ data: photo.data, label: '整改照片 ' + (index + 1), kind: 'rectification' });
    });
    return photos;
  }

  async function drawInspectionSheet(pdfDocument, page, font, record, pageNumber, totalPages) {
    const colors = pdfColors();
    const pageWidth = page.getWidth();
    const margin = 24;
    const tableWidth = pageWidth - margin * 2;
    const top = 812;
    const titleHeight = 46;
    const infoHeight = 46;
    const headerHeight = 34;
    const itemCount = Math.max(1, record.items.length);
    const itemTableHeight = 400;
    const rowHeight = Math.min(50, itemTableHeight / itemCount);
    const summaryHeight = 112 + Math.max(0, itemTableHeight - rowHeight * itemCount);
    const itemFontScale = Math.max(0.82, Math.min(1, rowHeight / 50));
    const signatureHeight = 120;
    const columns = [88, 31, 218, 73, tableWidth - 410];

    const pageHeader = formatDateZh(record.date) + '   第' + pageNumber + '页 共' + totalPages + '页';
    page.drawText(pageHeader, {
      x: pageWidth - margin - font.widthOfTextAtSize(pageHeader, 8),
      y: 826,
      size: 8,
      font: font,
      color: colors.muted
    });

    let currentTop = top;
    drawPdfCell(page, font, getSheetTitle(record), margin, currentTop, tableWidth, titleHeight, {
      size: 23,
      align: 'center',
      valign: 'middle',
      letterSpacing: 2.2,
      borderWidth: 1,
      borderColor: colors.border,
      textColor: colors.ink
    });
    currentTop -= titleHeight;

    const infoColumns = [76, 122, 78, tableWidth - 276];
    let x = margin;
    drawPdfCell(page, font, '检查时间：', x, currentTop, infoColumns[0], infoHeight, pdfLabelOptions(colors)); x += infoColumns[0];
    drawPdfCell(page, font, formatDateZh(record.date), x, currentTop, infoColumns[1], infoHeight, pdfValueOptions(colors)); x += infoColumns[1];
    drawPdfCell(page, font, '检查地点：', x, currentTop, infoColumns[2], infoHeight, pdfLabelOptions(colors)); x += infoColumns[2];
    drawPdfCell(page, font, record.location || '', x, currentTop, infoColumns[3], infoHeight, Object.assign(pdfValueOptions(colors), { size: 9.3, maxLines: 3 }));
    currentTop -= infoHeight;

    x = margin;
    ['检查类别', '序号', '检查内容及标准', '检查结果', '现场情况/问题'].forEach(function (text, index) {
      drawPdfCell(page, font, text, x, currentTop, columns[index], headerHeight, {
        size: 10.5,
        align: 'center',
        valign: 'middle',
        fill: colors.header,
        borderColor: colors.border,
        textColor: colors.ink,
        padding: 3,
        lineHeight: 12
      });
      x += columns[index];
    });
    currentTop -= headerHeight;

    record.items.forEach(function (item, index) {
      const categorySize = 9.6 * itemFontScale;
      const categoryLineHeight = categorySize * 1.25;
      const standardSize = 8.9 * itemFontScale;
      const standardLineHeight = standardSize * 1.25;
      const issueSize = 8.1 * itemFontScale;
      const issueLineHeight = issueSize * 1.25;
      x = margin;
      drawPdfCell(page, font, item.category, x, currentTop, columns[0], rowHeight, Object.assign(pdfValueOptions(colors), {
        size: categorySize,
        lineHeight: categoryLineHeight,
        padding: 3,
        align: 'center',
        maxLines: Math.max(1, Math.floor((rowHeight - 6) / categoryLineHeight))
      })); x += columns[0];
      drawPdfCell(page, font, String(index + 1), x, currentTop, columns[1], rowHeight, Object.assign(pdfValueOptions(colors), { size: 10.5 * itemFontScale, align: 'center' })); x += columns[1];
      drawPdfCell(page, font, item.standard, x, currentTop, columns[2], rowHeight, Object.assign(pdfValueOptions(colors), {
        size: standardSize,
        lineHeight: standardLineHeight,
        padding: 3,
        maxLines: Math.max(1, Math.floor((rowHeight - 6) / standardLineHeight))
      })); x += columns[2];
      drawResultCell(page, font, item.result, x, currentTop, columns[3], rowHeight, colors); x += columns[3];
      drawPdfCell(page, font, item.result === 'no' ? (item.issue || '') : '', x, currentTop, columns[4], rowHeight, Object.assign(pdfValueOptions(colors), {
        size: issueSize,
        lineHeight: issueLineHeight,
        padding: 3,
        maxLines: Math.max(1, Math.floor((rowHeight - 6) / issueLineHeight))
      }));
      currentTop -= rowHeight;
    });

    const issueText = record.items.filter(function (item) { return item.result === 'no'; }).map(function (item) {
      return item.category + '：' + (item.issue || '发现问题');
    }).join('；');
    const statusText = record.rectification && record.rectification.completed ? '整改状态：已整改完成。' : (issueText ? '整改状态：待整改。' : '检查结果：未发现问题。');
    const opinionText = record.rectification && record.rectification.opinion ? '整改意见：' + record.rectification.opinion : '';
    x = margin;
    drawPdfCell(page, font, '检查情况：', x, currentTop, columns[0], summaryHeight, Object.assign(pdfLabelOptions(colors), { valign: 'top', paddingTop: 8 })); x += columns[0];
    drawPdfCell(page, font, [issueText, opinionText, statusText].filter(Boolean).join('\n'), x, currentTop, tableWidth - columns[0], summaryHeight, Object.assign(pdfValueOptions(colors), { valign: 'top', size: 8.8, lineHeight: 12.2, padding: 8, maxLines: 8 }));
    currentTop -= summaryHeight;

    const inspectorLabelWidth = columns[0];
    const inspectorAreaWidth = tableWidth / 2 - inspectorLabelWidth;
    const inspectedLabelWidth = columns[0];
    const inspectedAreaWidth = tableWidth - inspectorLabelWidth - inspectorAreaWidth - inspectedLabelWidth;
    x = margin;
    drawPdfCell(page, font, '检查人签名：', x, currentTop, inspectorLabelWidth, signatureHeight, Object.assign(pdfLabelOptions(colors), { size: 10.2 })); x += inspectorLabelWidth;
    drawPdfCell(page, font, '', x, currentTop, inspectorAreaWidth, signatureHeight, pdfValueOptions(colors));
    page.drawLine({ start: { x: x, y: currentTop - signatureHeight / 2 }, end: { x: x + inspectorAreaWidth, y: currentTop - signatureHeight / 2 }, thickness: 0.65, color: colors.border });
    page.drawText('1.', { x: x + 6, y: currentTop - 17, size: 9, font: font, color: colors.ink });
    page.drawText('2.', { x: x + 6, y: currentTop - signatureHeight / 2 - 17, size: 9, font: font, color: colors.ink });
    await drawPdfImageContained(pdfDocument, page, record.signatures.inspector1, x + 24, currentTop - signatureHeight / 2 + 5, inspectorAreaWidth - 30, signatureHeight / 2 - 10);
    await drawPdfImageContained(pdfDocument, page, record.signatures.inspector2, x + 24, currentTop - signatureHeight + 5, inspectorAreaWidth - 30, signatureHeight / 2 - 10);
    x += inspectorAreaWidth;
    drawPdfCell(page, font, '被检查人签名：', x, currentTop, inspectedLabelWidth, signatureHeight, Object.assign(pdfLabelOptions(colors), { size: 10 })); x += inspectedLabelWidth;
    drawPdfCell(page, font, '', x, currentTop, inspectedAreaWidth, signatureHeight, pdfValueOptions(colors));
    await drawPdfImageContained(pdfDocument, page, record.signatures.inspected, x + 5, currentTop - signatureHeight + 10, inspectedAreaWidth - 10, signatureHeight - 20);
  }

  async function drawPhotoAppendixPage(pdfDocument, page, font, record, photos, pageNumber, totalPages) {
    const colors = pdfColors();
    const pageWidth = page.getWidth();
    const margin = 28;
    const contentWidth = pageWidth - margin * 2;
    const title = getPhotoAppendixTitle(record);
    page.drawText(title, {
      x: (pageWidth - font.widthOfTextAtSize(title, 20)) / 2,
      y: 798,
      size: 20,
      font: font,
      color: colors.ink
    });
    const meta = '检查日期：' + formatDateZh(record.date) + '    检查地点：' + (record.location || '');
    drawPdfLines(page, font, meta, margin, 775, contentWidth - 100, 9, 12, 2, colors.muted, 'left');
    const pageText = '第' + pageNumber + '页 共' + totalPages + '页';
    page.drawText(pageText, { x: pageWidth - margin - font.widthOfTextAtSize(pageText, 8.5), y: 775, size: 8.5, font: font, color: colors.muted });
    page.drawLine({ start: { x: margin, y: 758 }, end: { x: pageWidth - margin, y: 758 }, thickness: 0.8, color: colors.border });

    const gapX = 14;
    const gapY = 14;
    const boxWidth = (contentWidth - gapX) / 2;
    const boxHeight = 330;
    const firstTop = 746;
    for (let index = 0; index < photos.length; index++) {
      const column = index % 2;
      const row = Math.floor(index / 2);
      const x = margin + column * (boxWidth + gapX);
      const top = firstTop - row * (boxHeight + gapY);
      await drawPhotoBox(pdfDocument, page, font, photos[index], x, top, boxWidth, boxHeight, colors);
    }
    page.drawText(getRecordTypeName(record) + ' · 照片为检查时或整改后上传，已包含时间与地点水印', {
      x: margin,
      y: 26,
      size: 7.6,
      font: font,
      color: colors.muted
    });
  }

  async function drawPhotoBox(pdfDocument, page, font, photo, x, top, width, height, colors) {
    page.drawRectangle({ x: x, y: top - height, width: width, height: height, borderColor: colors.border, borderWidth: 0.8, color: colors.photoBackground });
    const captionHeight = 27;
    page.drawRectangle({ x: x, y: top - height, width: width, height: captionHeight, color: photo.kind === 'rectification' ? colors.successSoft : colors.header });
    const captionWidth = font.widthOfTextAtSize(photo.label, 9.4);
    page.drawText(photo.label, { x: x + (width - captionWidth) / 2, y: top - height + 8, size: 9.4, font: font, color: colors.ink });
    try {
      await drawPdfImageContained(pdfDocument, page, photo.data, x + 5, top - height + captionHeight + 5, width - 10, height - captionHeight - 10);
    } catch (_) {
      const missing = '照片无法读取';
      page.drawText(missing, { x: x + (width - font.widthOfTextAtSize(missing, 10)) / 2, y: top - height / 2, size: 10, font: font, color: colors.muted });
    }
  }

  function drawPdfCell(page, font, text, x, top, width, height, options) {
    const opts = options || {};
    const colors = pdfColors();
    const rectangle = {
      x: x,
      y: top - height,
      width: width,
      height: height,
      borderColor: opts.borderColor || colors.border,
      borderWidth: opts.borderWidth == null ? 0.65 : opts.borderWidth
    };
    if (opts.fill) rectangle.color = opts.fill;
    page.drawRectangle(rectangle);
    if (!text) return;
    const size = opts.size || 9.5;
    const lineHeight = opts.lineHeight || size * 1.35;
    const padding = opts.padding == null ? 5 : opts.padding;
    const maxLines = opts.maxLines || Math.max(1, Math.floor((height - padding * 2) / lineHeight));
    const lines = wrapPdfText(font, String(text), size, Math.max(1, width - padding * 2), maxLines);
    const blockHeight = lines.length * lineHeight;
    let firstBaseline;
    if (opts.valign === 'top') firstBaseline = top - (opts.paddingTop == null ? padding : opts.paddingTop) - size;
    else if (opts.valign === 'bottom') firstBaseline = top - height + padding + blockHeight - lineHeight + (lineHeight - size);
    else firstBaseline = top - (height - blockHeight) / 2 - size;
    lines.forEach(function (line, index) {
      let textX = x + padding;
      const lineWidth = font.widthOfTextAtSize(line, size);
      if (opts.align === 'center') textX = x + Math.max(padding, (width - lineWidth) / 2);
      if (opts.align === 'right') textX = x + width - padding - lineWidth;
      page.drawText(line, {
        x: textX,
        y: firstBaseline - index * lineHeight,
        size: size,
        font: font,
        color: opts.textColor || colors.ink,
        characterSpacing: opts.letterSpacing || 0
      });
    });
  }

  function drawResultCell(page, font, result, x, top, width, height, colors) {
    drawPdfCell(page, font, '', x, top, width, height, pdfValueOptions(colors));
    const scale = Math.max(0.78, Math.min(1, height / 50));
    const boxSize = 9 * scale;
    const textSize = 9.2 * scale;
    const gap = Math.max(2.4, 4 * scale);
    const blockHeight = boxSize * 3 + gap * 2;
    const topPadding = Math.max(1.5, (height - blockHeight) / 2);
    const textWidth = font.widthOfTextAtSize('是', textSize);
    const groupWidth = boxSize + 4 * scale + textWidth;
    const boxX = x + (width - groupWidth) / 2;
    const textX = boxX + boxSize + 4 * scale;
    const firstY = top - topPadding - boxSize;
    const secondY = firstY - gap - boxSize;
    const thirdY = secondY - gap - boxSize;
    drawPdfCheckbox(page, boxX, firstY, boxSize, result === 'yes', colors);
    drawPdfCheckbox(page, boxX, secondY, boxSize, result === 'no', colors);
    drawPdfCheckbox(page, boxX, thirdY, boxSize, result === 'na', colors);
    page.drawText('是', { x: textX, y: firstY - 0.8 * scale, size: textSize, font: font, color: colors.ink });
    page.drawText('否', { x: textX, y: secondY - 0.8 * scale, size: textSize, font: font, color: colors.ink });
    page.drawText('不适用', { x: textX, y: thirdY - 0.8 * scale, size: textSize * 0.72, font: font, color: colors.ink });
  }

  function drawPdfCheckbox(page, x, y, size, checked, colors) {
    page.drawRectangle({ x: x, y: y, width: size, height: size, borderColor: colors.ink, borderWidth: 0.8 });
    if (!checked) return;
    page.drawLine({ start: { x: x + 1.5, y: y + size * 0.48 }, end: { x: x + size * 0.4, y: y + 1.8 }, thickness: 1.2, color: colors.blue });
    page.drawLine({ start: { x: x + size * 0.4, y: y + 1.8 }, end: { x: x + size - 1.2, y: y + size - 1.5 }, thickness: 1.2, color: colors.blue });
  }

  function drawPdfLines(page, font, text, x, top, width, size, lineHeight, maxLines, color, align) {
    const lines = wrapPdfText(font, text, size, width, maxLines);
    lines.forEach(function (line, index) {
      const lineWidth = font.widthOfTextAtSize(line, size);
      let textX = x;
      if (align === 'center') textX = x + (width - lineWidth) / 2;
      if (align === 'right') textX = x + width - lineWidth;
      page.drawText(line, { x: textX, y: top - size - index * lineHeight, size: size, font: font, color: color });
    });
  }

  function wrapPdfText(font, rawText, size, maxWidth, maxLines) {
    const paragraphs = String(rawText == null ? '' : rawText).replace(/\r/g, '').split('\n');
    const lines = [];
    paragraphs.forEach(function (paragraph, paragraphIndex) {
      if (!paragraph) {
        if (lines.length < maxLines) lines.push('');
        return;
      }
      let line = '';
      Array.from(paragraph).forEach(function (char) {
        const candidate = line + char;
        if (line && font.widthOfTextAtSize(candidate, size) > maxWidth) {
          if (lines.length < maxLines) lines.push(line);
          line = char;
        } else {
          line = candidate;
        }
      });
      if (line && lines.length < maxLines) lines.push(line);
      if (paragraphIndex < paragraphs.length - 1 && lines.length < maxLines && !line) lines.push('');
    });
    if (!lines.length) return [''];
    const limited = lines.slice(0, maxLines);
    if (lines.length > maxLines && limited.length) {
      let last = limited[limited.length - 1];
      while (last.length && font.widthOfTextAtSize(last + '…', size) > maxWidth) last = last.slice(0, -1);
      limited[limited.length - 1] = last + '…';
    }
    return limited;
  }

  async function drawPdfImageContained(pdfDocument, page, dataUrl, x, y, width, height) {
    if (!dataUrl) return;
    const commaIndex = dataUrl.indexOf(',');
    const header = dataUrl.slice(0, commaIndex).toLowerCase();
    const bytes = base64ToUint8Array(dataUrl.slice(commaIndex + 1));
    const image = header.indexOf('png') >= 0 ? await pdfDocument.embedPng(bytes) : await pdfDocument.embedJpg(bytes);
    const scale = Math.min(width / image.width, height / image.height);
    const drawWidth = image.width * scale;
    const drawHeight = image.height * scale;
    page.drawImage(image, {
      x: x + (width - drawWidth) / 2,
      y: y + (height - drawHeight) / 2,
      width: drawWidth,
      height: drawHeight
    });
  }

  function pdfLabelOptions(colors) {
    return {
      size: 10.5,
      align: 'center',
      valign: 'middle',
      fill: colors.label,
      borderColor: colors.border,
      textColor: colors.ink,
      padding: 4,
      lineHeight: 13
    };
  }

  function pdfValueOptions(colors) {
    return {
      size: 9.5,
      valign: 'middle',
      borderColor: colors.border,
      textColor: colors.ink,
      padding: 5,
      lineHeight: 12.5
    };
  }

  function pdfColors() {
    const rgb = window.PDFLib.rgb;
    return {
      ink: rgb(0.08, 0.11, 0.16),
      muted: rgb(0.35, 0.4, 0.48),
      border: rgb(0.22, 0.25, 0.29),
      blue: rgb(0.08, 0.35, 0.84),
      header: rgb(0.84, 0.89, 0.96),
      label: rgb(0.92, 0.92, 0.92),
      successSoft: rgb(0.87, 0.95, 0.9),
      photoBackground: rgb(0.97, 0.97, 0.97)
    };
  }

  if (typeof window !== 'undefined') {
    window.__carShedOfflineTest = {
      createRecordPdf: createRecordPdf,
      createCombinedPdf: createCombinedPdf,
      createCombinedPdfFromSources: createCombinedPdfFromSources,
      blankRecord: blankRecord,
      formatDateZh: formatDateZh,
      loadPdfFont: loadPdfFont,
      base64ChunksToUint8Array: base64ChunksToUint8Array,
      getHolidayMeta: getHolidayMeta,
      prepareImportedRecords: prepareImportedRecords,
      previewSyncMerge: previewSyncMerge,
      validateRecord: validateRecord,
      renderHome: renderHome,
      renderCalendar: renderCalendar,
      getFilteredRecords: getFilteredRecords,
      selectFilteredRecords: selectFilteredRecords,
      getCombinedExportFileName: getCombinedExportFileName,
      mergeInspectionTypes: mergeInspectionTypes,
      state: state
    };
  }

})();
