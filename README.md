# Pizarra Dev — Bloque de Notas mejorado

App Android de pizarra infinita (Kotlin, vistas puras, Room). El código fuente ahora vive en la raíz del repo (`app/`, `build.gradle.kts`, etc.) y `proyecto.zip` se regenera con el mismo contenido para que el workflow de GitHub Actions siga funcionando.

## Bloque Nota: qué cambió

### 1. Título editable (siempre visible)
- Cada nota tiene un campo **Título** de una línea en la parte superior (`Título de la nota…`).
- Se guarda en la base de datos (nueva columna `title`, migración Room v1 → v2, sin perder notas existentes).
- Enter en el título salta al cuerpo. El título nunca cambia de modo (siempre texto normal en negrita).

### 2. Flechita para expandir / contraer (▼ / ▲)
- En la cabecera de la nota hay una flechita:
  - **▼ (colapsado):** solo se ve título + vista previa del cuerpo (2 líneas). Tocar la vista previa también expande.
  - **▲ (expandido):** se muestra la **interfaz completa de escritura** (barra de herramientas + cuerpo editable con scroll vertical y altura máxima).
- Las notas cargadas desde la base empiezan **colapsadas** para que el lienzo se vea limpio. Las notas **nuevas** empiezan **expandidas** con el foco en el título, listas para escribir.

### 3. Modo normal al principio + opción “Agregar script” solo en ese bloque
- Toda nota nueva nace en **modo normal** (texto con ajuste de línea).
- Dentro de la interfaz expandida hay un botón **“+ Script”**. Al tocarlo, **solo ese bloque** cambia a modo script (monoespaciado, sin ajuste, scroll horizontal, botón TAB y auto-indentación).
- En modo script el botón pasa a **“Aa Texto”** para volver a modo normal. El cambio se guarda por bloque (`code`) y no afecta a las demás notas.
- En la vista previa colapsada, una nota en modo script se reconoce por el prefijo `</>`.

## Archivos tocados
- `app/src/main/java/com/pizarra/dev/data/Db.kt` — columna `title`, DAO `setTitle`, `Repo.title()`, migración v1→v2.
- `app/src/main/java/com/pizarra/dev/ui/BlockView.kt` — título, vista previa, flecha ▼/▲, interfaz expandida con scroll, botón “+ Script” / “Aa Texto” por bloque.
- `app/src/main/java/com/pizarra/dev/ui/BoardView.kt` — crea notas expandidas y en modo normal, guarda título, maneja expandir y borrado (título + cuerpo).

## Compilación
El workflow `.github/workflows/build.yml` extrae `proyecto.zip` y compila el APK release. Tras editar el código, regenera el zip con la misma estructura (archivos en la raíz del zip):

```bash
zip -r proyecto.zip build.gradle.kts settings.gradle.kts gradle.properties app
```
