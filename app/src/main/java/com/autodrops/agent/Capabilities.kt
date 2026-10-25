package com.autodrops.agent

/**
 * v3.17 Capabilities/Help model. The ToolRegistry stays the single source of truth; this adds
 * Ukrainian descriptions and a human gating label per risk. Pure and unit-testable.
 */
data class CapabilityRow(val name: String, val description: String, val risk: Risk, val gating: String)

object Capabilities {
    /** Ukrainian, user-facing gating level for each risk class (mirrors AgentViewModel's confirmation policy). */
    fun gatingUa(risk: Risk): String = when (risk) {
        Risk.LOW -> "Без підтвердження (читання/безпечне)"
        Risk.SENSITIVE -> "Підтвердження (або авто-дозвіл читань у сесії)"
        Risk.EFFECT -> "Потрібне підтвердження (змінює стан)"
        Risk.HIGH -> "Потрібне підтвердження (небезпечно)"
        Risk.ELEVATED -> "Потрібне підтвердження (підвищені права)"
    }

    fun riskUa(risk: Risk): String = when (risk) {
        Risk.LOW -> "НИЗЬКИЙ"
        Risk.SENSITIVE -> "ЧУТЛИВИЙ"
        Risk.EFFECT -> "ВПЛИВ"
        Risk.HIGH -> "ВИСОКИЙ"
        Risk.ELEVATED -> "ПІДВИЩЕНИЙ"
    }

    /** Build capability rows from the registry's (name, englishDescription, baseRisk) triples. */
    fun rows(specs: List<Triple<String, String, Risk>>): List<CapabilityRow> =
        specs.map { (name, desc, risk) ->
            CapabilityRow(name, ToolDocs.ua(name) ?: desc, risk, gatingUa(risk))
        }
}

/** Optional Ukrainian descriptions keyed by tool name. Falls back to the registry's own description. */
object ToolDocs {
    private val UA = mapOf(
        "network_info" to "Показує поточне Wi-Fi-підключення та локальні IP; за запитом сканує сусідні мережі.",
        "lan_scan" to "Сканує поширені TCP-порти у твоїй поточній підмережі.",
        "ping" to "Перевіряє доступність хоста (ping).",
        "dns_lookup" to "Перетворює ім'я хоста на IP-адреси.",
        "discover_devices" to "Знаходить пристрої в LAN через mDNS/NSD та SSDP/UPnP (ТВ, колонки, Cast, DLNA).",
        "dlna_control" to "Керує DLNA/UPnP-рендерером (ТВ/колонка): відтворення, пауза/стоп, гучність.",
        "adbtv" to "ADB по мережі для Android TV: клавіші, запуск застосунку, shell.",
        "cast_control" to "Google Cast у твоїй Wi-Fi: відтворити URL, зупинити, статус (лише приватні IP).",
        "speak" to "Озвучує текст уголос через системний TTS (надає перевагу українській).",
        "apps_list" to "Перелік застосунків на телефоні (назва + пакет).",
        "app_launch" to "Запускає встановлений застосунок за назвою пакета.",
        "toggle_settings" to "Змінює/відкриває налаштування: Wi-Fi, Bluetooth, яскравість, гучність, DND, ліхтарик.",
        "file_op" to "Операції з файлами в робочій теці застосунку: список/читання/запис/видалення/mkdir.",
        "shell" to "Виконує команду в пісочниці (sh -c); бекенд — у налаштуваннях.",
        "file_list" to "Показує дерево файлів робочої теки.",
        "file_read" to "Читає текстовий файл із робочої теки.",
        "file_write" to "Створює/перезаписує текстовий файл у робочій теці (за підтвердженням).",
        "run_script" to "Запускає скрипт у пісочниці й повертає вивід у чат.",
        "termux_run" to "Запускає Python/Node/bash через Termux (потрібен Termux + RUN_COMMAND).",
        "list_apps" to "Перелік встановлених застосунків із фільтром.",
        "launch_app" to "Запускає застосунок за пакетом.",
        "notifications" to "Читає активні сповіщення (потрібен доступ Notification Listener).",
        "clipboard" to "Читає або записує буфер обміну (лише на передньому плані).",
        "contacts" to "Шукає контакти за іменем (потрібен READ_CONTACTS).",
        "send_sms" to "Відкриває застосунок SMS із заповненим текстом (ти тиснеш «надіслати»).",
        "sms_read" to "Читає вхідні SMS (потрібен READ_SMS).",
        "sms_send" to "Надсилає SMS напряму через радіо (коштує грошей, досягає третіх осіб).",
        "notification_post" to "Публікує локальне сповіщення на цьому пристрої.",
        "ui_action" to "Керування UI через Accessibility: дамп екрана, клік/введення, тап/свайп, back/home/recents.",
        "security_report" to "Сканує підмережу та готує звіт безпеки (ризиковані відкриті порти).",
        "app_permissions" to "Показує дозволи встановленого застосунку і які з них небезпечні.",
        "web_fetch" to "Завантажує публічну веб-сторінку й повертає читабельний текст (приватні адреси заборонені).",
        "net_discover" to "Знаходить живі хости твоєї підмережі (ping-sweep + ARP/MAC + імена).",
        "ping_sweep" to "Ping-sweep твоєї підмережі для переліку доступних хостів.",
        "tcp_scan" to "TCP-скан портів ОДНОГО хоста у твоїй приватній мережі (публічні IP заборонені).",
        "udp_scan" to "UDP-проба портів ОДНОГО хоста у твоїй приватній мережі.",
        "banner_grab" to "Читає банер сервісу на відкритому порту приватного хоста (лише читання).",
        "open_services" to "Підсумовує відкриті поширені сервіси ОДНОГО приватного хоста.",
        "reverse_dns" to "Зворотне перетворення IP на ім'я хоста.",
        "traceroute" to "Трасує маршрут/доступність до хоста (лише читання).",
        "whois" to "WHOIS-запит для домену.",
        "ssl_cert_info" to "Читає деталі TLS-сертифіката хоста:порт (лише читання).",
        "packet_capture" to "Статус/інструкції локального перехоплення пакетів (потрібна окрема згода VPN/root).",
        "arp_scan" to "ARP-скан підмережі: хости з MAC та виробником.",
        "os_detect" to "nmap -A стиль для приватного хоста: вгадування ОС за TTL + версії сервісів.",
        "mac_vendor" to "Визначає виробника за MAC (OUI).",
        "rag_add" to "Зберігає нотатку в локальну базу знань (on-device embeddings).",
        "rag_search" to "Семантичний пошук у локальній базі знань.",
        "rag_list" to "Перелік записів бази знань.",
        "rag_delete" to "Видаляє запис бази знань за id.",
        "screen_ocr" to "Читає текст із екрана (OCR) з останнього дозволеного захоплення.",
        "set_plan" to "Оголошує план кроків, що показується у панелі плану.",
        "complete_step" to "Позначає крок плану виконаним/невдалим.",
        "inventory_add" to "Зберігає/оновлює пристрій LAN в інвентарі (лише приватні IP).",
        "inventory_list" to "Перелік збережених пристроїв LAN із мітками.",
        "inventory_label" to "Встановлює мітку для збереженого пристрою за IP."
    )
    fun ua(name: String): String? = UA[name]
}
