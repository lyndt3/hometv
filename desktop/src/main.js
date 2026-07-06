const { invoke } = window.__TAURI__.core;

// State Variables
let allStreams = [];
let categories = [];
let currentlySelectedStreams = [];
let currentCategory = '';
let currentStream = null;
let hlsPlayer = null;
let mpegtsPlayer = null;
let epgData = {}; // tvgId -> [{start, stop, title, desc}]

// DOM Elements
const splashScreen = document.getElementById('splash-screen');
const splashStatus = document.getElementById('splash-status');
const categoriesList = document.getElementById('categories-list');
const channelsList = document.getElementById('channels-list');
const searchInput = document.getElementById('search-input');
const videoPlayer = document.getElementById('video-player');
const videoLoader = document.getElementById('video-loader');
const epgOverlay = document.getElementById('epg-overlay');
const epgChannelName = document.getElementById('epg-channel-name');
const epgNow = document.getElementById('epg-now');
const epgNext = document.getElementById('epg-next');
const loginModal = document.getElementById('login-modal');
const loginForm = document.getElementById('login-form');
const btnLogin = document.getElementById('btn-login');
const btnRefresh = document.getElementById('btn-refresh');
const btnCloseLogin = document.getElementById('btn-close-login');

// Normalizer rules for sorting channels
const DIACRITICS_REGEX = /[\u0300-\u036f]/g;
function getSortIndex(name) {
  const upper = name.toUpperCase();
  const isPortuguese = upper.startsWith("PT") || 
                       upper.includes("PORTUGAL") || 
                       upper.includes("RTP") || 
                       upper.includes("SIC") || 
                       upper.includes("TVI") || 
                       upper.includes("SPORT TV") || 
                       upper.includes("SPORTTV") || 
                       upper.includes("DAZN") || 
                       upper.includes("ELEVEN");

  if (!isPortuguese) return 1000;

  let clean = upper.normalize("NFD")
    .replace(DIACRITICS_REGEX, "")
    .toLowerCase()
    .replace("pt:", "")
    .replace("pt -", "")
    .replace("pt :", "")
    .replace("fhd", "")
    .replace("hd", "")
    .replace("sd", "")
    .replace("4k", "")
    .replace("hevc", "")
    .replace("h265", "")
    .replace("h264", "")
    .replace("1080p", "")
    .replace("(teste)", "")
    .replace("(indisponivel)", "")
    .trim();

  if (/^rtp\s*1\b/.test(clean)) return 1;
  if (/^rtp\s*2\b/.test(clean)) return 2;
  if (clean.startsWith("sic") && !clean.includes("not") && !clean.includes("rad") && !clean.includes("mul") && !clean.includes("car") && !clean.includes(" k")) return 3;
  if (clean.startsWith("tvi") && !clean.includes("fic") && !clean.includes("rea") && !clean.includes("24") && !clean.includes("pla")) return 4;
  if (clean.includes("sic") && clean.includes("not")) return 5;
  if (/^rtp\s*3\b/.test(clean)) return 6;
  if (clean.includes("porto") && clean.includes("canal")) return 7;
  if (clean.includes("tvi") && clean.includes("fic")) return 8;
  if (clean.includes("cnn")) return 9;
  if (clean.includes("cmtv") || (clean.includes("cm") && clean.includes("tv"))) return 10;
  if (clean.includes("rtp") && clean.includes("mem")) return 11;
  if (clean.includes("sic") && clean.includes("mul")) return 12;
  if (clean.includes("sic") && clean.includes("rad")) return 13;
  if (clean.includes("sic") && clean.includes("car")) return 14;
  if (clean.includes("tvi") && clean.includes("rea")) return 15;
  if (clean.includes("canal q")) return 16;
  if (clean.includes("globo")) return 17;
  if (clean.includes("rtp") && (clean.includes("aco") || clean.includes("acor"))) return 18;
  if (clean.includes("rtp") && clean.includes("mad")) return 19;
  if (clean.includes("canal 11") || clean.includes("canal11")) return 20;

  if (clean.includes("sport") && clean.includes("tv")) {
    if (clean.includes("1")) return 21;
    if (clean.includes("2")) return 22;
    if (clean.includes("3")) return 23;
    if (clean.includes("4")) return 24;
    if (clean.includes("5")) return 25;
    if (clean.includes("6")) return 26;
    if (clean.includes("7")) return 27;
    return 28;
  }

  if (clean.includes("dazn") || clean.includes("eleven")) {
    if (clean.includes("1")) return 29;
    if (clean.includes("2")) return 30;
    if (clean.includes("3")) return 31;
    if (clean.includes("4")) return 32;
    if (clean.includes("5")) return 33;
    if (clean.includes("6")) return 34;
    return 35;
  }

  if (clean.includes("tvi") && clean.includes("24")) return 36;
  if (clean.includes("tvi") && clean.includes("pla")) return 37;
  if (clean.includes("euronews")) return 38;
  if (clean.includes("rtv")) return 39;
  if (clean.includes("local")) return 40;

  if (clean.includes("hollywood")) return 41;
  if (clean.includes("tv cine") || clean.includes("tvcine")) {
    if (clean.includes("top") || clean.includes("1")) return 42;
    if (clean.includes("edition") || clean.includes("2")) return 43;
    if (clean.includes("emotion") || clean.includes("3")) return 44;
    if (clean.includes("action") || clean.includes("4")) return 45;
    return 46;
  }
  if (clean.includes("cinemundo")) return 47;
  if (clean.includes("axn") && clean.includes("white")) return 48;
  if (clean.includes("axn") && clean.includes("mov")) return 49;
  if (clean.includes("axn")) return 50;
  if (clean.includes("fox") && clean.includes("life")) return 51;
  if (clean.includes("fox") && clean.includes("cri")) return 52;
  if (clean.includes("fox") && clean.includes("mov")) return 53;
  if (clean.includes("fox") && clean.includes("com")) return 54;
  if (clean.includes("fox")) return 55;
  if (clean.includes("star") && clean.includes("life")) return 51;
  if (clean.includes("star") && clean.includes("cri")) return 52;
  if (clean.includes("star") && clean.includes("mov")) return 53;
  if (clean.includes("star") && clean.includes("com")) return 54;
  if (clean.includes("star")) return 55;
  if (clean.includes("syfy")) return 56;
  if (clean.includes("amc")) return 57;

  if (clean.includes("national") || clean.includes("nat geo") || clean.includes("natgeo")) return 58;
  if (clean.includes("discovery")) return 59;
  if (clean.includes("odisseia")) return 60;
  if (clean.includes("history") || clean.includes("historia")) return 61;

  return 1000;
}

// Initialise Credentials
function getCredentials() {
  const server = localStorage.getItem('iptv_server') || 'http://x96.us:8880';
  const user = localStorage.getItem('iptv_user') || 'FerreiraLindote';
  const pass = localStorage.getItem('iptv_pass') || '123digalaoutravez1';
  return { server, user, pass };
}

// Parse attributes from M3U line
function parseAttribute(line, key) {
  const match = line.match(new RegExp(`${key}="([^"]*)"`));
  return match ? match[1] : '';
}

// Parse M3U string into structured JSON
function parseM3u(data) {
  const lines = data.split('\n');
  const tempStreams = [];
  const tempCategories = new Set();

  let currentName = '';
  let currentLogo = '';
  let currentTvgId = '';
  let currentGroup = 'Outros';

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith('#EXTINF:')) {
      currentLogo = parseAttribute(line, 'tvg-logo');
      currentTvgId = parseAttribute(line, 'tvg-id');
      const groupAttr = parseAttribute(line, 'group-title');

      const commaIndex = line.lastIndexOf(',');
      currentName = commaIndex !== -1 ? line.substring(commaIndex + 1).trim() : 'Canal Sem Nome';
      
      if (groupAttr) {
        currentGroup = groupAttr;
      } else {
        if (/PT:|PT\s-|PT\s:/.test(currentName)) {
          currentGroup = 'Portugal';
        } else if (/ES:|ES\s-/.test(currentName)) {
          currentGroup = 'Espanha';
        } else {
          currentGroup = 'Outros';
        }
      }
    } else if (line.length > 0 && !line.startsWith('#')) {
      if (currentGroup !== 'Delimiter' && !currentName.includes('⭐⭐⭐') && !currentName.includes('---')) {
        tempCategories.add(currentGroup);
        
        let streamId = tempStreams.length + 1;
        try {
          const lastPart = line.substring(line.lastIndexOf('/') + 1);
          const cleanId = lastPart.split('.')[0];
          if (!isNaN(cleanId)) streamId = parseInt(cleanId);
        } catch(e) {}

        tempStreams.push({
          name: currentName,
          logo: currentLogo,
          tvgId: currentTvgId,
          group: currentGroup,
          url: line,
          id: streamId,
          sortIndex: getSortIndex(currentName)
        });
      }
      currentName = '';
      currentLogo = '';
      currentTvgId = '';
      currentGroup = 'Outros';
    }
  }

  // Convert Categories Set to Sorted Array
  categories = Array.from(tempCategories).map(name => ({ id: name, name })).sort((a, b) => a.name.localeCompare(b.name));
  
  // Sort Streams: first by index, then alphabetically
  allStreams = tempStreams.sort((a, b) => {
    if (a.sortIndex !== b.sortIndex) return a.sortIndex - b.sortIndex;
    return a.name.localeCompare(b.name);
  });
}

// Fetch XMLTV EPG
async function fetchAndParseEpg() {
  const { server, user, pass } = getCredentials();
  const epgUrl = `${server}/xmltv.php?username=${user}&password=${pass}`;
  try {
    console.log("A descarregar XMLTV EPG...");
    const xmlText = await invoke('fetch_m3u', { url: epgUrl });
    const parser = new DOMParser();
    const xmlDoc = parser.parseFromString(xmlText, "text/xml");
    const programmes = xmlDoc.getElementsByTagName('programme');
    
    epgData = {};
    for (let i = 0; i < programmes.length; i++) {
      const prog = programmes[i];
      const channel = prog.getAttribute('channel');
      const start = prog.getAttribute('start');
      const stop = prog.getAttribute('stop');
      const titleNode = prog.getElementsByTagName('title')[0];
      const descNode = prog.getElementsByTagName('desc')[0];
      
      if (channel && start && stop && titleNode) {
        if (!epgData[channel]) epgData[channel] = [];
        epgData[channel].push({
          start: parseEpgDate(start),
          stop: parseEpgDate(stop),
          title: titleNode.textContent,
          desc: descNode ? descNode.textContent : ''
        });
      }
    }
    console.log("EPG Carregado com sucesso.");
    updateCurrentEpgDisplay();
  } catch (e) {
    console.error("Erro ao carregar EPG:", e);
  }
}

// Parse EPG Date string (YYYYMMDDHHMMSS +/-ZZZZ)
function parseEpgDate(str) {
  try {
    const year = parseInt(str.substring(0, 4));
    const month = parseInt(str.substring(4, 6)) - 1;
    const day = parseInt(str.substring(6, 8));
    const hour = parseInt(str.substring(8, 10));
    const minute = parseInt(str.substring(10, 12));
    const second = parseInt(str.substring(12, 14));
    return new Date(Date.UTC(year, month, day, hour, minute, second));
  } catch (e) {
    return new Date();
  }
}

// Get EPG programs for stream
function getEpgPrograms(tvgId) {
  if (!tvgId || !epgData[tvgId]) return { now: null, next: null };
  const list = epgData[tvgId];
  const nowTime = new Date();
  
  let currentProg = null;
  let nextProg = null;

  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (nowTime >= p.start && nowTime <= p.stop) {
      currentProg = p;
      // The next one is usually the following index
      if (i + 1 < list.length) nextProg = list[i + 1];
      break;
    }
  }
  return { now: currentProg, next: nextProg };
}

// Update UI view models
function renderCategories() {
  categoriesList.innerHTML = '';
  categories.forEach(cat => {
    const el = document.createElement('div');
    el.className = `category-item ${currentCategory === cat.id ? 'active' : ''}`;
    el.innerText = cat.name;
    el.onclick = () => selectCategory(cat.id);
    categoriesList.appendChild(el);
  });
}

function selectCategory(catId) {
  currentCategory = catId;
  renderCategories();
  
  currentlySelectedStreams = allStreams.filter(s => s.group === catId);
  renderChannels();
}

function renderChannels(filterText = '') {
  channelsList.innerHTML = '';
  const search = filterText.toLowerCase();
  
  const filtered = currentlySelectedStreams.filter(s => 
    s.name.toLowerCase().includes(search)
  );

  filtered.forEach(stream => {
    const epg = getEpgPrograms(stream.tvgId);
    
    const card = document.createElement('div');
    card.className = `channel-card ${currentStream && currentStream.id === stream.id ? 'active' : ''}`;
    
    card.innerHTML = `
      <div class="channel-logo-container">
        ${stream.logo ? `<img class="channel-logo" src="${stream.logo}" alt="" onerror="this.style.display='none'" />` : ''}
      </div>
      <div class="channel-card-info">
        <div class="channel-name">${stream.name}</div>
        <div class="channel-epg-now">${epg.now ? epg.now.title : 'Sem info de programação'}</div>
      </div>
    `;
    
    card.onclick = () => playStream(stream);
    channelsList.appendChild(card);
  });
}

// Custom Format EPG hours
function formatTime(date) {
  if (!date) return '';
  const hrs = String(date.getHours()).padStart(2, '0');
  const mins = String(date.getMinutes()).padStart(2, '0');
  return `${hrs}:${mins}`;
}

// Play Selected Stream
function playStream(stream) {
  currentStream = stream;
  
  // Highlight active card
  document.querySelectorAll('.channel-card').forEach(el => el.classList.remove('active'));
  renderChannels(searchInput.value);

  const rawUrl = stream.url;
  const streamUrl = `http://127.0.0.1:18087/proxy?url=${encodeURIComponent(rawUrl)}`;
  console.log(`A reproduzir stream via proxy: ${stream.name} -> ${streamUrl}`);
  
  // Show video loading indicator
  videoLoader.classList.remove('hidden');
  epgOverlay.classList.add('hidden');

  // Stop previous decoders
  destroyPlayers();

  const isM3u8 = streamUrl.includes('.m3u8');
  
  if (isM3u8) {
    if (Hls.isSupported()) {
      hlsPlayer = new Hls();
      hlsPlayer.loadSource(streamUrl);
      hlsPlayer.attachMediaElement(videoPlayer);
      hlsPlayer.on(Hls.Events.MANIFEST_PARSED, () => {
        videoPlayer.play();
        videoLoader.classList.add('hidden');
        showEpgOverlay(stream);
      });
      hlsPlayer.on(Hls.Events.ERROR, (event, data) => {
        console.error("Hls Player Error:", data);
      });
    } else if (videoPlayer.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari native support
      videoPlayer.src = streamUrl;
      videoPlayer.addEventListener('loadedmetadata', () => {
        videoPlayer.play();
        videoLoader.classList.add('hidden');
        showEpgOverlay(stream);
      });
    }
  } else {
    // Treat as MPEG-TS
    if (mpegts.getFeatureList().mseLivePlayback) {
      mpegtsPlayer = mpegts.createPlayer({
        type: 'mpegts',
        isLive: true,
        url: streamUrl
      });
      mpegtsPlayer.attachMediaElement(videoPlayer);
      mpegtsPlayer.load();
      mpegtsPlayer.play();
      
      mpegtsPlayer.on(mpegts.Events.INFO, (info) => {
        videoLoader.classList.add('hidden');
        showEpgOverlay(stream);
      });
      
      mpegtsPlayer.on(mpegts.Events.ERROR, (type, detail, info) => {
        console.error("Mpegts Player Error Type:", type, "Detail:", detail);
        videoLoader.classList.add('hidden');
      });
    } else {
      // Direct html5 fallback
      videoPlayer.src = streamUrl;
      videoPlayer.play();
      videoLoader.classList.add('hidden');
      showEpgOverlay(stream);
    }
  }
}

function destroyPlayers() {
  if (hlsPlayer) {
    hlsPlayer.destroy();
    hlsPlayer = null;
  }
  if (mpegtsPlayer) {
    mpegtsPlayer.unload();
    mpegtsPlayer.detachMediaElement();
    mpegtsPlayer.destroy();
    mpegtsPlayer = null;
  }
  videoPlayer.src = '';
}

function showEpgOverlay(stream) {
  epgChannelName.innerText = stream.name;
  updateCurrentEpgDisplay();
  epgOverlay.classList.remove('hidden');
  
  // Hide overlay after 6 seconds
  setTimeout(() => {
    if (currentStream && currentStream.id === stream.id) {
      epgOverlay.classList.add('hidden');
    }
  }, 6000);
}

function updateCurrentEpgDisplay() {
  if (!currentStream) return;
  const epg = getEpgPrograms(currentStream.tvgId);
  if (epg.now) {
    epgNow.innerText = `A Dar: ${formatTime(epg.now.start)} - ${epg.now.title}`;
  } else {
    epgNow.innerText = 'A Dar: Sem informação de programação';
  }
  if (epg.next) {
    epgNext.innerText = `A Seguir: ${formatTime(epg.next.start)} - ${epg.next.title}`;
  } else {
    epgNext.innerText = 'A Seguir: --';
  }
}

// Load Application Data
async function loadData(forceRefresh = false) {
  const { server, user, pass } = getCredentials();
  
  splashScreen.classList.remove('hidden');
  splashStatus.innerText = "A ligar ao servidor IPTV...";

  const m3uUrl = `${server}/get.php?username=${user}&password=${pass}&type=m3u_plus&output=ts`;
  
  try {
    console.log("A descarregar M3U online...");
    const m3uData = await invoke('fetch_m3u', { url: m3uUrl });
    
    splashStatus.innerText = "A analisar lista de canais...";
    parseM3u(m3uData);
    
    splashStatus.innerText = "A inicializar interface...";
    if (categories.length > 0) {
      // Prioritize Portugal Live TV category specifically, then fallback to general Portugal, then first category
      const defaultCat = categories.find(c => {
        const name = c.name.toLowerCase();
        return name.includes('portugal') && (name.includes('tv') || name.includes('live'));
      }) || categories.find(c => c.name.toLowerCase().includes('portugal')) || categories[0];
      
      selectCategory(defaultCat.id);
    }
    
    // Fade out splash
    splashScreen.classList.add('hidden');
    
    // Fetch EPG in background
    fetchAndParseEpg();
  } catch (e) {
    console.error(e);
    splashStatus.innerText = `Erro ao ligar ao servidor: ${e.message || e}`;
    setTimeout(() => {
      openLoginModal();
    }, 2000);
  }
}

// Modal management
function openLoginModal() {
  const { server, user, pass } = getCredentials();
  document.getElementById('input-server').value = server;
  document.getElementById('input-username').value = user;
  document.getElementById('input-password').value = pass;
  loginModal.classList.remove('hidden');
}

function closeLoginModal() {
  loginModal.classList.add('hidden');
}

// Event Bindings
loginForm.onsubmit = (e) => {
  e.preventDefault();
  const server = document.getElementById('input-server').value;
  const user = document.getElementById('input-username').value;
  const pass = document.getElementById('input-password').value;
  
  localStorage.setItem('iptv_server', server);
  localStorage.setItem('iptv_user', user);
  localStorage.setItem('iptv_pass', pass);
  
  closeLoginModal();
  loadData(true);
};

btnLogin.onclick = openLoginModal;
btnCloseLogin.onclick = closeLoginModal;
btnRefresh.onclick = () => loadData(true);

searchInput.oninput = (e) => {
  renderChannels(e.target.value);
};

// Initial Startup
window.addEventListener('DOMContentLoaded', () => {
  // Garantir que a mensagem "A carregar transmissão..." desaparece assim que o vídeo começa a dar
  videoPlayer.onplaying = () => {
    videoLoader.classList.add('hidden');
  };
  videoPlayer.onwaiting = () => {
    videoLoader.classList.remove('hidden');
  };
  videoPlayer.onerror = () => {
    videoLoader.classList.add('hidden');
  };

  // Suporte a Ecrã Inteiro (Fullscreen) nativo do Tauri v2 com fallback
  const { getCurrentWindow } = window.__TAURI__.window;
  const appWindow = getCurrentWindow();
  const videoContainer = document.querySelector('.video-container');
  const appContainer = document.querySelector('.app-container');
  
  async function toggleFullscreen() {
    try {
      const isFullscreen = await appWindow.isFullscreen();
      await appWindow.setFullscreen(!isFullscreen);
    } catch (e) {
      console.error("Erro ao alterar ecrã inteiro nativo:", e);
      if (!document.fullscreenElement) {
        videoContainer.requestFullscreen().catch(err => {
          videoPlayer.requestFullscreen().catch(e => console.error(e));
        });
      } else {
        document.exitFullscreen().catch(e => console.error(e));
      }
    }
  }

  // Monitorizar alterações de tamanho para atualizar as classes de fullscreen da app
  window.addEventListener('resize', async () => {
    try {
      const isFull = await appWindow.isFullscreen();
      if (isFull) {
        appContainer.classList.add('fullscreen-active');
      } else {
        appContainer.classList.remove('fullscreen-active', 'show-menu');
      }
    } catch (e) {
      console.error(e);
    }
  });

  // Duplo clique no leitor para entrar/sair de ecrã inteiro
  videoPlayer.addEventListener('dblclick', toggleFullscreen);
  videoContainer.addEventListener('dblclick', (e) => {
    if (e.target === videoContainer || e.target === videoPlayer) {
      toggleFullscreen();
    }
  });

  // Fechar o menu flutuante se clicar no leitor
  videoPlayer.addEventListener('click', () => {
    if (appContainer.classList.contains('show-menu')) {
      appContainer.classList.remove('show-menu');
    }
  });

  // Botão direito do rato para abrir/fechar o menu em fullscreen
  window.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (appContainer.classList.contains('fullscreen-active')) {
      appContainer.classList.toggle('show-menu');
    }
  });

  // Atalhos de teclado (F para fullscreen, Setas para mudar canal)
  window.addEventListener('keydown', (e) => {
    // Tecla F: Alterna Ecrã Inteiro
    if (e.key.toLowerCase() === 'f') {
      toggleFullscreen();
      return;
    }

    // Setas Cima/Baixo: Mudar de canal (apenas se não estiver a escrever na pesquisa)
    if (document.activeElement === searchInput) return;

    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (currentlySelectedStreams.length > 0) {
        let index = currentlySelectedStreams.findIndex(s => s.id === currentStream?.id);
        
        if (e.key === 'ArrowDown') {
          // Próximo canal
          index = index === -1 ? 0 : (index + 1) % currentlySelectedStreams.length;
        } else {
          // Canal anterior
          index = index === -1 ? currentlySelectedStreams.length - 1 : (index - 1 + currentlySelectedStreams.length) % currentlySelectedStreams.length;
        }
        
        const nextStream = currentlySelectedStreams[index];
        if (nextStream) {
          playStream(nextStream);
          // Fechar menu flutuante ao mudar de canal se estiver aberto
          appContainer.classList.remove('show-menu');
        }
      }
    }
  });

  loadData();
});
