# MythicShields — Вики

**MythicShields** — аддон для [MythicMobs](https://mythiccraft.io/), добавляющий полноценную механику щитов для кастомных предметов: настоящий `isBlocking() == true` в Minecraft, резисты по типам урона, прочность/ресурс, регенерацию и кулдауны.

---

## Содержание

1. [Установка](#1-установка)
2. [Как работает механика](#2-как-работает-механика)
3. [Настройка щита в предмете](#3-настройка-щита-в-предмете)
4. [Справочник опций](#4-справочник-опций)
5. [Типы ресурса](#5-типы-ресурса)
6. [Поломка щита](#6-поломка-щита)
7. [Резисты по типам урона](#7-резисты-по-типам-урона)
8. [Скиллы щита: ~onTakedShieldDamage](#8-скиллы-щита-ontakedshielddamage)
9. [Арифметические выражения](#9-арифметические-выражения)
10. [Глобальный config.yml аддона](#10-глобальный-configyml-аддона)
11. [Команды и права](#11-команды-и-права)
12. [Примеры готовых щитов](#12-примеры-готовых-щитов)
13. [Частые вопросы (FAQ)](#13-частые-вопросы-faq)

---

## 1. Установка

1. Установите **MythicMobs** (5.6+).
2. Положите `MythicShields-1.0.0.jar` в папку `plugins/`.
3. Перезапустите сервер. Аддон подхватится как зависимость MythicMobs.

Требования: Java 21, Paper 1.21.5+ (для `Mode: COMPONENT`; `VANILLA_SWAP` работает и на 1.21.4).

> Настройка щитов идёт через `plugins/MythicMobs/Items/*.yml` (секция `Shield:` прямо в предмете) **и/или** через собственные файлы аддона `plugins/MythicShields/Items/*.yml` — там же включается автосоответствие предметам по `Match.*` (материал + CustomModelData), если предмет выдан не MythicMobs.

---

## 2. Как работает механика

### 2.1. `Mode: COMPONENT` (по умолчанию, Paper 1.21.5+)

Кастомный предмет (Echo Shard, меч, любой материал + `CustomModelData`) **сам становится настоящим ванильным щитом** через компонент `blocks_attacks`:

```
Аддон обнаруживает зарегистрированный щит в руках/инвентаре
(по клику, смене слота, входу, подбору) и один раз навешивает
компонент minecraft:blocks_attacks
        │
        ▼
Игрок зажимает ПКМ → предмет в руке НАСТОЯЩИЙ щит:
анимация поднятия, задержка блока, isBlocking() == true (стандартные звуки выключены)
        │
        ▼
Правила урона (резисты, ProtectFrom, прочность, ресурс) применяет аддон
своим конфигом — предмет НИКОГДА не подменяется
```

Параметры компонента настраиваются в секции `Shield.Component:` (см. ниже). По умолчанию `Component` сохраняет ванильную анимацию и правила блокировки, но стандартные звуки блока/поломки выключены, чтобы их можно было задать через MythicMobs Skills. Задержка блока — 0 сек., правило снижения урона компонентом отключено (урон считает аддон).

### 2.2. `Mode: VANILLA_SWAP` (для 1.21.4 и ниже)

Старая механика hand-swap: предмет временно заменяется на vanilla `SHIELD`, при отпускании возвращается. Внешний вид при блоке теряется, но механика работает.

---

## 3. Настройка щита в предмете

Добавьте секцию `Shield:` в любой предмет MythicMobs в `plugins/MythicMobs/Items/`. Nexo-аналог `iron_sword_shield` (Echo Shard + CustomModelData) выглядит так:

```yaml
BladeShield:
  Id: ECHO_SHARD
  Display: '<gold>Короткий клинок - щит</gold>'
  Lore:
  - '<gray>Зажми ПКМ, чтобы блокировать.</gray>'
  Shield:
    Enabled: true
    Mode: COMPONENT          # предмет сам блокирует как ванильный щит (Paper 1.21.5+)

    Durability:
      Max: '80'               # максимум прочности
      CostPerBlock: '2'       # расход за каждый заблокированный удар
      BreakThreshold: 0       # при <= этого значения щит ломается
      OnBreak: STAY_BROKEN    # что происходит при поломке

    Blocking:
      DamageReduction: 0.35   # -35% урона при блокировке
      DamageMultiplier: 0.9   # итоговый урон * 0.9
      KnockbackResistance: 0.7
      Resistances:
        PROJECTILE: 0.5       # -50% урона снарядами при блокировке
        ENTITY_ATTACK: 0.4

    Regen:
      Amount: '1'             # +1 прочности за тик регенерации
      Interval: 60            # каждые 60 тиков
      DelayAfterBlock: 40     # задержка после последнего блока (тики)
      RegenWhileBlocking: false

    RaiseCooldown:
      Ticks: 40               # кулдаун на поднятие после опускания
      OnBreakTicks: 100       # кулдаун после поломки
      Message: '&cЩит ещё не готов! (&e{time} &cсек.)'
```

Ничего из `Component` обычно указывать не нужно — по умолчанию стандартные звуки выключены (механика и анимация блока остаются ванильными), правила урона у компонента нулевые, урон контролирует аддон. Если нужно вернуть звук, задайте `BlockSound` и/или `DisableSound`. **Задержка активации блока по умолчанию 0** — щит встаёт мгновенно. Если нужен ванильный «разгон» щита, задайте `BlockDelaySeconds: 0.25`. Настроить можно так:

```yaml
    Component:
      BlockDelaySeconds: 0.0     # 0 = щит активен сразу; ванильное значение — 0.25 сек.
      DisableCooldownScale: 1.0  # масштаб кулдауна от оружия с weapon.disable_blocking_for_seconds (0 = нельзя сломать оружием)
      BlockSound: item.shield.block
      DisableSound: item.shield.break
      BypassedBy: '#minecraft:bypasses_shield'  # тег урона, пробивающий щит
      ItemDamage:                # расход прочности ПРЕДМЕТА ванилью
        Threshold: 0
        Base: 0
        Factor: 0                # 0 = ваниль не тратит прочность предмета
      DamageReductions:          # встроенные в предмет правила снижения урона (по умолчанию пусто!)
        - Base: 0
          Factor: 0.5            # пример: ваниль съедает половину снарядов
          Angle: 90
          Type: '#minecraft:is_projectile'
```

> Если задаёте `Component.DamageReductions`, помните о двойном снижении: сначала ванильный компонент урежет урон, потом свои правила применит аддон. По умолчанию список пуст, и урон полностью контролируют опции `Blocking.*`.

### `Mode`

| Значение | Поведение |
|---|---|
| `COMPONENT` (по умолчанию) | Предмет сам становится настоящим щитом через ванильный компонент `blocks_attacks`; предмет никогда не подменяется, модель/имя всегда видны. Требует Paper 1.21.5+ |
| `VANILLA_SWAP` | Старая механика hand-swap: предмет на время блока заменяется на vanilla `SHIELD` (для 1.21.4 и ниже) |

Аддон определяет предмет в руке по PDC-ключу `mythicmobs:item_id` — MythicMobs сам проставляет его своим предметам, поэтому выданные MM предметы распознаются автоматически.

Предметы без MM-ключей (Nexo, выданные вручную, из креатива) распознаются двумя путями:

1. Команда `/mythicshields bind <id>` — привязать предмет в руке к щиту вручную;
2. **Автосоответствие `Match.*`** — создайте файл `plugins/MythicShields/Items/<имя>.yml`:

```yaml
BladeShield:
  Match:
    Material: ECHO_SHARD      # материал предмета (обязателен либо CustomModelData)
    CustomModelData: 10000    # и/или CustomModelData предмета (число)
  # ...далее та же структура, что и секция Shield в MM-предмете:
  Durability:
    Max: '80'
    CostPerBlock: '2'
  Blocking:
    DamageReduction: 0.35
    Resistances:
      PROJECTILE: 0.5
  Regen:
    Amount: '1'
    Interval: 60
    DelayAfterBlock: 40
```

3. **Автовывод из MM-предмета**: если предмет в `plugins/MythicMobs/Items/*.yml` задан с `Id:`/`Material:` и `CustomModelData`, аддон выведет Match сам — достаточно, например:

```yaml
BladeShield:
  Id: ECHO_SHARD              # для MM-предметов ключ Id (Material тоже читается)
  CustomModelData: 10000
  Lore:
  - '<gray>Зажми ПКМ, чтобы блокировать.</gray>'
  Shield:
    Enabled: true
    # ...
```

Предмет, который попадает под Match (явный или выведенный), аддон находит сам при любом появлении в инвентаре — привязывать вручную ничего не нужно.

---

## 4. Справочник опций

### `Durability`
| Опция | По умолчанию | Описание |
|---|---|---|
| `Max` | 100 | Максимальная прочность щита. Прочность хранится между подъёмами — регенерация восстанавливает её и вне блока |
| `CostPerBlock` | 2 | Расход за каждый заблокированный удар. Может зависеть от урона: плейсхолдеры `<damage>` (урон удара) и `<resist>` (текущий резист с учётом спада). Пример: `'<damage> * <resist>'` — при уроне 10 и резисте 0.5 расход будет 5 |
| `BreakThreshold` | 0 | При прочности <= значения щит ломается |
| `OnBreak` | STAY_BROKEN | Действие при поломке (см. [раздел 6](#6-поломка-щита)): `STAY_BROKEN`, `VANISH` (алиас `BREAK_ITEM`), `DROP_TO_INVENTORY`, `REPAIR` |

### `Blocking`
| Опция | По умолчанию | Описание |
|---|---|---|
| `DamageReduction` | 0.3 | Доля урона, вычитаемая при блокировке (0.3 = -30%). Не работает при включённом `ResistDecay` |
| `ResistDecay` | — | Динамический резист (см. ниже): резист спадает с максимума до минимума по мере удержания |
| `DamageMultiplier` | 1.0 | Итоговый урон умножается на значение |
| `KnockbackResistance` | 0.5 | Сопротивление отдаче при блокировке (0..1). Отбрасывание гасится **после** события урона (на тик позже), 1.0 = игрок не сдвигается с места |
| `DisableLikeVanillaOnBreak` | false | При поломке щита — ванильная анимация disable (звук задаётся `Component.DisableSound`, по умолчанию выключен), поверх любого `OnBreak` |
| `ProtectFrom` | ALL | От чего щит защищает: `ALL` — от всего, либо список типов урона (`ENTITY_ATTACK`, `PROJECTILE`, ...). Незащищённый урон проходит целиком, прочность за него не тратится |
| `IgnoreTypes` | — | Типы урона, которые **всегда** пробивают щит целиком, даже при `ALL` |
| `Resistances` | — | Секции по типам урона (см. [раздел 7](#7-резисты-по-типам-урона)) |

**Разница `DamageReduction` и `DamageMultiplier`:** итоговый урон считается как `урон × (1 − DamageReduction) × DamageMultiplier`. `DamageReduction` — «сила блока» (сколько поглотил щит), `DamageMultiplier` — глобальный коэффициент поверх результата. Если нужен один параметр — задайте только `DamageReduction`, а `DamageMultiplier: 1.0` не трогайте.

### `Regen`
| Опция | По умолчанию | Описание |
|---|---|---|
| `Amount` | 0.5 | Восстановление за тик регенерации |
| `Interval` | 40 | Период регенерации (в тиках) |
| `DelayAfterBlock` | 60 | Задержка после последнего блока (в тиках) |
| `RegenWhileBlocking` | false | Регенерировать даже при зажатой блокировке |

### `RaiseCooldown`
| Опция | По умолчанию | Описание |
|---|---|---|
| `Ticks` | 0 | Кулдаун, который начисляется **в момент опускания щита** (отпустил ПКМ → щит сразу на КД). Пока он идёт, `blocks_attacks` снят с предмета: щит физически не блокирует, а на предмете в руке виден ванильный откат. По окончании компонент возвращается автоматически (0 = без кулдауна). Заменяет старую систему AntiSpam |
| `OnBreakTicks` | 100 | Кулдаун после поломки щита |
| `Message` | — | Сообщение при попытке поднять на кулдауне. `{time}` = оставшиеся секунды |

> `RaiseCooldown.AntispamTicks` больше не используется. Если он остался в старом конфиге, аддон прочитает его как обычный `RaiseCooldown.Ticks` (при условии, что `Ticks` не задан явно).

### `Resource` (см. [раздел 5](#5-типы-ресурса))
| Опция | По умолчанию | Описание |
|---|---|---|
| `Type` | DURABILITY | `DURABILITY`, `HP`, `VARIABLE` |
| `Variable` | — | Имя переменной (для `VARIABLE`) |
| `Cost` | 1 | Расход за каждый блок |
| `MinResource` | 1 | Минимум ресурса для блокировки |
| `RequireOnBlock` | true | Опускать щит, когда ресурс < MinResource |

---

## 5. Типы ресурса

### `DURABILITY` (по умолчанию)
Прочность хранится внутриаддонно. Поломка при `Durability.BreakThreshold`.

### `HP`
Расход при блокировке — здоровье игрока. Не опускается ниже 1.0.

### `VARIABLE`
Расход — значение переменной, хранящейся в PDC игрока:

```yaml
    Resource:
      Type: VARIABLE
      Variable: shield_resource
      Cost: '1'
      MinResource: '1'
      RequireOnBlock: true
```

---

## 6. Поломка щита

При `Durability.OnBreak`:

| Значение | Что происходит |
|---|---|
| `STAY_BROKEN` | Щит опускается, уходит в кулдаун `RaiseCooldown.OnBreakTicks`; прочность **НЕ восстанавливается** — после КД щит поднять можно, но он сломается снова, пока регенерация не отстроит прочность |
| `VANISH` (алиас `BREAK_ITEM`) | Предмет уничтожается; дается кулдаун `RaiseCooldown.OnBreakTicks` |
| `DROP_TO_INVENTORY` | Предмет опускается и остаётся у игрока (без восстановления прочности); дается кулдаун |
| `REPAIR` | Прочность сразу восстанавливается до Max; щит недоступен `RaiseCooldown.OnBreakTicks`, потом работает как новый |

В любом случае срабатывает `~onCrackShield` и ставится кулдаун на поднятие. Стандартный звук поломки выключен; добавьте собственный звук через `Skills` или задайте `Component.DisableSound`. Пока КД идёт, щит поднять нельзя — при попытке придёт сообщение `RaiseCooldown.Message`.

### Ванильный disable-эффект при поломке: `Blocking.DisableLikeVanillaOnBreak`

При `true` поломка щита от прочности выглядит как ванильный disable (как будто щит сломали топором):

```yaml
    Blocking:
      DisableLikeVanillaOnBreak: true
```

- клиент показывает анимацию сломанного щита; стандартный звук выключен, если не задан `Component.DisableSound`,
- предмет на 1 тик снимается с руки и возвращается —
- щит уходит в КД `RaiseCooldown.OnBreakTicks` и не поднимается, пока КД активен.

Работает поверх любого `OnBreak`, кроме `VANISH` (предмет уничтожается — показывать нечего).

### Ванильный disable удара топором: `Component.AxeDisableTicks`

Как у ванильного щита: удар топором по блокирующему игроку выводит щит из строя на N тиков. Задаётся на щит:

```yaml
    Component:
      AxeDisableTicks: 100   # 5 секунд КД после удара топором (0 = выключено)
```

или глобально в `config.yml`:

```yaml
Defaults:
  Component:
    AxeDisableTicks: 100
```

При ударе топором (любой материал `_AXE`) по блокирующему игроку щит мгновенно опускается и недоступен указанное число тиков; звук стандартно выключен, а `~onStartShieldCooldown` запускает пользовательский эффект. Приоритет: значение щита > глобальное > 0 (выключено).

---

## 7. Резисты по типам урона

Секция `Blocking.Resistances` принимает ключи = имена `EntityDamageEvent.DamageCause`:

| Ключ | Тип урона |
|---|---|
| `ENTITY_ATTACK` | Атака сущности |
| `PROJECTILE` | Снаряд (стрела, трезубец и т.п.) |
| `ENTITY_EXPLOSION` | Взрыв (крип) |
| `BLOCK_EXPLOSION` | Взрыв блока (TNT) |
| `FIRE` / `FIRE_TICK` | Огонь |
| `LAVA` | Лава |
| `LIGHTNING` | Молния |
| `MAGIC` | Магический урон |
| `DRAGON_BREATH` | Дыхание дракона |
| `FALL` | Падение |

Полный список — `EntityDamageEvent.DamageCause` в javadocs Bukkit.

```yaml
      Resistances:
        PROJECTILE: 0.5
        ENTITY_ATTACK: 0.4
        MAGIC: 0.0        # магический урон проходит полностью
```

Резист применяется к урону **до** общего `DamageMultiplier`.

### Уточнение скоупа защиты

По умолчанию щит защищает **от всего**. Двумя опциями можно сузить или ослабить защиту:

```yaml
    Blocking:
      ProtectFrom:          # щит защищает ТОЛЬКО от перечисленного
        - ENTITY_ATTACK
        - PROJECTILE
        - ENTITY_EXPLOSION
      IgnoreTypes:          # но MAGIC всегда проходит целиком
        - MAGIC
```

- Урон, от которого щит **не** защищает (`ProtectFrom` выбран списком, тип не указан), проходит в полном объёме, прочность за него не тратится, щит остаётся поднят.
- `IgnoreTypes` работает и при `ProtectFrom: ALL`, и поверх `Resistances`.

### Динамический резист: `Blocking.ResistDecay`

Логика ванильного «старта с полного блока», но с раздельными фазами: при поднятии щита резист максимальный и держится им в течение `StartDuration` тиков (фаза полного блока), затем **плавно, линейно, каждый тик чуть ниже** — за `DelayEndMin` тиков опускается до минимума:

```yaml
    Blocking:
      ResistDecay:
        Enabled: true
        Start: 1.0          # Max резист при поднятии щита (1.0 = 100% урона блокируется)
        Min: 0.2            # Min резист в конце (20%)
        Start_ticks: 20     # тики ПОЛНОГО резиста (20 тиков = 1 сек держится 100%)
        Delay_ticks: 100    # тики плавного линейного спада от Max до Min (100 тиков = 5 сек)
```

Пример выше: поднял щит → 1 секунда держит 100% блок → 5 секунд плавно падает до 20%.

Резкий вариант (полный блок, потом мгновенный сброс):

```yaml
    Blocking:
      ResistDecay:
        Enabled: true
        Start: 1.0
        Min: 0.0            # после спада блок полностью исчезает
        Start_ticks: 20     # 1 сек 100% блока
        Delay_ticks: 20     # затем резкий (быстрый) спад до 0% за 1 сек
```

- Отпустил блок → поднял снова: резист снова `Start` (100%).
- Пер-типовые `Resistances` имеют приоритет над спадом (переопределяют общий резист).
- `Min: 1.0` — вырожденный случай: постоянный полный блок, как у ванильного щита.
- При включённом `ResistDecay` статичный `DamageReduction` не используется.
- Обратная совместимость: старые опции `Seconds` / `StartDuration` / `DelayEndMin` всё ещё читаются, если новые `Start_ticks`/`Delay_ticks` не заданы.

Стамина (`DURABILITY` / `Resource`) всё так же расходуется за каждый заблокированный удар; при её поломке щит **опускается и уходит в кулдаун** (см. [раздел 6](#6-поломка-щита)).

### Полный блок без красного хита

Если резист (включая спад) поглощает **весь** урон, событие урона отменяется целиком — игрок не «краснеет» и не получает анимацию урона. При частичном блоке урон проходит, как задано `DamageReduction`/`DamageMultiplier`.

---

## 8. Скиллы щита: `~onTakedShieldDamage` и события

Щит может запускать скиллы MythicMobs на удар по блоку (с фильтром по **текущему резисту**) и на события жизненного цикла щита. Скиллы перечисляются в `Shield.Skills` **или** в корневой секции `Skills` предмета MythicMobs (как обычные MM-item скиллы) — аддон читает оба места и сам отбирает строки по своим триггерам.

```yaml
BladeShield:
  Id: ECHO_SHARD
  CustomModelData: 10000
  Shield:
    Enabled: true
    Blocking:
      ResistDecay:
        Enabled: true
        Start: 1.0        # первые StartTicks тиков резист 100%
        Min: 0.2          # затем спад до 20% за DelayTicks тиков
    Skills:
    - particle{particle=crit;amount=40;hspread=1;vspread=1} @self ~onTakedShieldDamage{Resistance=1}
    - particle{particle=crit;amount=40;hspread=1;vspread=1} @self ~onTakedShieldDamage{Resistance=0.2}
```

### Триггеры

| Триггер | Когда срабатывает | Фильтр |
|---|---|---|
| `~onTakedShieldDamage` | игрок получил удар, который принял щит | `{Resistance=X}` — по текущему резисту |
| `~onCrackShield` | щит сломался (прочность <= `BreakThreshold`), в момент `OnBreak` | — |
| `~onStartShieldCooldown` | начался кулдаун щита **не из-за поломки** (отпустил ПКМ, axe-disable) | — |
| `~onFullyShield` | щит полностью восстановился: КД закончился и прочность полная, либо регенерация достигла `Max` | — |

```yaml
Skills:
- sound{s=item.shield.block;p=0.8} @self ~onTakedShieldDamage
- sound{s=block.anvil.land;p=0.6} @self ~onCrackShield
- particle{particle=smoke;amount=15} @self ~onCrackShield
- sound{s=block.note_block.bass;p=0.5} @self ~onStartShieldCooldown
- sound{s=block.amethyst_block.chime;p=1.2} @self ~onFullyShield
- particle{particle=happy_villager;amount=10} @self ~onFullyShield
```

> Стандартные звуки блока и поломки по умолчанию **заглушены** (`minecraft:intentionally_empty`), поэтому звук можно полностью настроить в `Skills`. Для ручного звука задайте `Component.BlockSound` и/или `Component.DisableSound` (например, `item.shield.block`). Эти же настройки применяются к временному щиту в режиме `VANILLA_SWAP`.

В `Skills` можно писать **две вещи**:

1. **Имя скилла** из `plugins/MythicMobs/Skills/*.yml` — аддон найдёт его через SkillManager и выполнит;
2. **Inline-механику** — обычную строку MythicMobs (`particle{...}`, `sound{...}`, `skill{s=...}` и т. п.). Аргументы в фигурных скобках не трогаются, удаляется только сам триггер с его фильтром.

Именованный скилл выглядит так (в `plugins/MythicMobs/Skills/*.yml`):

```yaml
ShieldBang:
  Skills:
  - sound{s=block.enchantment_table.use;p=1.4} @ self
  - damage{amount=3} @ entitiesInRadius{r=4;viewers=self}
```

### Как читается строка скилла

| Часть | Значение |
|---|---|
| `firework` / `particle{...}` | Имя скилла MythicMobs **или** inline-механика |
| `@ self` | Цель. `@self` (по умолчанию) — игрок с щитом; `@damager` / `@trigger` / `@attacker` — атакующий |
| `~onTakedShieldDamage` | Триггер: удар, принятый щитом (см. таблицу триггеров выше) |
| `{Resistance=1}` | Фильтр резиста (`Resistance`/`Resist`). Значения >1 трактуются как проценты: `20` = `0.2` |

**Как сопоставляется резист:**

- `Resistance=1` (или `100`) — удар **полностью поглощён** щитом (итоговый урон 0) либо текущий резист ровно 100%. Это удобный «удар в полный блок».
- Любое другое значение — точное совпадение с текущим резистом (±0.005). Например, при `Blocking.DamageReduction: 0.35` нужен `{Resistance=0.35}`; при `ResistDecay` минимум `0.2` даёт `{Resistance=0.2}`.

```text
Skills:
- скилл1 @ self ~onTakedShieldDamage{Resistance=1}     # удар полностью заблокирован
- скилл2 @ self ~onTakedShieldDamage{Resistance=0.2}   # удар при резисте 20% (дно спада)
- скилл3 @ self ~onTakedShieldDamage{Resistance=35}    # 0.35, проценты
```

### Правила

- Строка без одного из триггеров щита **не выполняется** (защита от случайного запуска).
- `Skills` можно писать и в `Shield:`, и в корне предмета — это одно и то же.
- Фильтр `{Resistance=X}` можно опустить — скилл стреляет на любом резисте. Для событий `onCrackShield` / `onStartShieldCooldown` / `onFullyShield` фильтр не используется.
- Кастер и цель — сам игрок (MythicMobs-профиль игрока работает как SkillCaster), поэтому механики могут ссылаться на `<caster>`, `@self`, `@cone` и т. д.
- В метадату скилла пишется текущий резист: доступен в механиках через `<skill.metadata.shield_resist>` и урон удара — `<skill.metadata.shield_damage>`.
- Дедуп: одно и то же Bukkit-событие урона запускает `~onTakedShieldDamage` только один раз, но отдельные быстрые удары не теряются. Списание прочности по-прежнему ограничено 30 мс.
- Триггер `~onTakedShieldDamage` регистрируется в MythicMobs при старте аддона (запись в лог `Триггер ~onTakedShieldDamage зарегистрирован`).
- При `Debug: true` каждый пропуск по фильтру пишется в лог с фактическим резистом — удобно, чтобы подобрать `Resistance` под свой щит:
  `Скилл щита пропущен: Resistance=1.000, фактический резист 0.350 — строка: ...`

---

## 9. Арифметические выражения

Все значения могут быть арифметикой: `80 + tier * 20`, `(100 - damage) / 2`. Поддерживаются `+ - * /` и скобки. При ошибке вычисления используется значение по умолчанию и пишется запись в лог (`Debug: true`).

В `Durability.CostPerBlock` дополнительно доступны контекстные плейсхолдеры:

| Плейсхолдер | Значение |
|---|---|
| `<damage>` | Урон удара (до снижения щитом) |
| `<resist>` | Текущий резист щита (с учётом спада `ResistDecay`) |

Пример: `CostPerBlock: '<damage> * <resist>'` — при уроне меча 10 и резисте 0.5 расход будет ровно 5 прочности.

---

## 10. Глобальный config.yml аддона

`plugins/MythicShields/config.yml` — **только** глобальные значения по умолчанию (применяются, если опция не задана в предмете) и отладка:

```yaml
Debug: false

Defaults:
  Durability:
    Max: 100
    CostPerBlock: 2
    BreakThreshold: 0
    OnBreak: STAY_BROKEN
  Component:
    AxeDisableTicks: 0      # ванильный disable топором для всех щитов (0 = выключено)
  Regen:
    Amount: 0.5
    Interval: 40
    DelayAfterBlock: 60
    RegenWhileBlocking: false
  RaiseCooldown:
    Ticks: 0
    OnBreakTicks: 100
    Message: '&cЩит ещё не готов! (&e{time} &cсек.)'
  Blocking:
    DamageReduction: 0.3
    DamageMultiplier: 1.0
    KnockbackResistance: 0.5

Messages:
  Prefix: '&8[&6MythicShields&8]&r '
```

Логи: `plugins/MythicShields/logs/mythicshields.log`.

---

## 11. Команды и права

| Команда | Описание |
|---|---|
| `/mythicshields reload` | Перезагрузить config.yml и все предметы MythicMobs |
| `/mythicshields list` | Список загруженных щитов (с режимами) |
| `/mythicshields apply` | Надеть компонент `blocks_attacks` на предмет в главной руке (если предмет распознан как щит) |
| `/mythicshields bind <id>` | Привязать предмет в главной руке к щиту `<id>` и сразу надеть компонент. Для предметов, выданных не MythicMobs (Nexo и т.п.) |
| `/mythicshields unbind` | Убрать привязку с предмета в руке |

Право: `mythicshields.admin`.

---

## 12. Примеры готовых щитов

### Меч с блокировкой на прочности

```yaml
BastionShield:
  Id: NETHERITE_SWORD
  Display: '<gold>Бастион</gold>'
  Shield:
    Enabled: true
    Durability:
      Max: '80'
      CostPerBlock: '<damage> * <resist>'   # расход = урон удара × текущий резист
      BreakThreshold: 0
      OnBreak: STAY_BROKEN
    Blocking:
      DisableLikeVanillaOnBreak: true       # поломка = ванильный disable-эффект
      ResistDecay:
        Enabled: true
        Start: 1.0
        Min: 0.2
        Start_ticks: 20                   # 1 сек 100% блока
        Delay_ticks: 100                  # затем 5 сек плавный спад до 20%
      KnockbackResistance: 0.7
    Regen:
      Amount: '1'
      Interval: 60
      DelayAfterBlock: 40
      RegenWhileBlocking: false
    RaiseCooldown:
      Ticks: 40
      OnBreakTicks: 100
      Message: '&cЩит ещё не готов! (&e{time} &cсек.)'
    Component:
      AxeDisableTicks: 100                  # удар топором выводит щит из строя на 5 сек
```

### Усиливающий щит на HP

```yaml
BloodShield:
  Id: SHIELD
  Display: '<dark_red>Кровавый щит</dark_red>'
  Shield:
    Enabled: true
    Resource:
      Type: HP
      Cost: '1'
    Blocking:
      DamageReduction: 0.6
      Resistances:
        FIRE: 0.8
        MAGIC: 0.0
      KnockbackResistance: 1.0
```

### Тотем с запасом ресурса

```yaml
TotemStamina:
  Id: TOTEM_OF_UNDYING
  Display: '<aqua>Тотем выносливости</aqua>'
  Shield:
    Enabled: true
    Resource:
      Type: VARIABLE
      Variable: shield_resource
      Cost: '5'
      MinResource: '10'
      RequireOnBlock: true
    Blocking:
      DamageReduction: 0.2
      DamageMultiplier: 0.8
      KnockbackResistance: 0.3
    Durability:
      Max: '50'
      CostPerBlock: '5'
      OnBreak: REPAIR
```

---

## 13. Частые вопросы (FAQ)

**Q: Блокировка не активируется.**
A: Диагностика по шагам:
1. `Debug: true` в `plugins/MythicShields/config.yml`, перезапустите сервер.
2. Возьмите предмет в руку и выполните `/mythicshields apply`. Если ответ «предмет не распознан» — предмет выдан не MythicMobs: выполните `/mythicshields bind <id>` (id из `/mythicshields list`).
3. Если bind применился, а блок всё равно не поднимается — переключите слот в горячей панели и обратно (компонент должен попасть в руку, которую клиент реально держит аддонанным предметом).
4. Смотрите `logs/mythicshields.log`: строка `blocks_attacks применён к предмету щита ...` — компонент на предмете.
5. Проверьте: `Shield.Enabled: true`; сервер Paper 1.21.5+ для режима `COMPONENT` (иначе ставьте `Mode: VANILLA_SWAP`).

**Q: Предмет в руке не показывает блокирлекательную анимацию, когда я его только что получил.**
A: Компонент `blocks_attacks` надевается на предмет, как только он замечен аддоном (вход в игру, смена слота, подбор, клик ПКМ). Первый зажатый ПКМ после выдачи предмета может пройти без анимации — уже со второго всё нативно. Если хочется 100% сразу — возьмите предмет в руку и поменяйте слот туда-обратно.

**Q: Условие `blocking` в MythicMobs не срабатывает.**
A: В режиме `COMPONENT` предмет стал настоящим щитом, `isBlocking()` работает нативно, поэтому `?blocking` и условие `blocking` работают. Проверьте `Debug: true`.

**Q: Как изменить скорость регенерации?**
A: `Regen.Interval` (период) и `Regen.Amount` (сколько за период). Задержка после блока — `Regen.DelayAfterBlock`.

**Q: Можно ли задать резист только для одного типа урона?**
A: Да — укажите нужные ключи в `Blocking.Resistances`. Не указанные типы получают общий `DamageReduction`.

**Q: Как сделать, чтобы резист ослабевал при долгом удержании щита?**
A: Включите `Blocking.ResistDecay` (см. [раздел 7](#7-резисты-по-типам-урона)): `Start_ticks` тиков держится полный резист, затем плавно спадает до `Min` за `Delay_ticks` тиков.

**Q: Как сделать, чтобы удар топором выводил щит из строя как в ванили?**
A: Задайте `Component.AxeDisableTicks: 100` (на щите или глобально в `Defaults.Component.AxeDisableTicks`) — удар топором по блокирующему игроку опустит щит и заблокирует поднятие на указанное число тиков.

**Q: Почему при полном блоке я всё равно получаю урон/краснею?**
A: Проверьте, что резист реально равен 1.0 (при `ResistDecay` — пока идёт фаза `StartDuration`). При полном блоке событие урона отменяется целиком и красной вспышки нет; при частичном — урон проходит как задано.

**Q: Кулдаун общий для всех щитов?**
A: Кулдаун индивидуален для каждого игрока, но не различает предметы. При попытке поднять любой щит на кулдауне сработает его сообщение.
