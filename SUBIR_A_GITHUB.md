# Guía: Subir a GitHub

## Antes de subir — limpiar la API Key del código

Abre `app/src/main/java/com/example/home_page_intento1/DriveVideoRepository.kt` y cambia la línea:

```kotlin
var API_KEY: String = "AIzaSy..."   // ← borra la clave real
```

por:

```kotlin
var API_KEY: String = "TU_API_KEY_AQUI"
```

Guarda el archivo. La clave real la sigues usando solo localmente.

---

## Pasos para subir el repo (desde terminal o PowerShell)

```powershell
# 1. Situarse en la raíz del proyecto (esta carpeta)
cd "C:\Users\diego\Desktop\Tesis_lcl\Prototipo_v1_0"

# 2. Inicializar git (si no existe ya)
git init

# 3. Primer commit
git add .
git commit -m "feat: LSCTalker v1.0 - prototipo funcional TG"

# 4. Crear el repo en GitHub
#    → Ve a https://github.com/new
#    → Nombre: LSCTalker (o el que prefieras)
#    → Visibilidad: Privado
#    → NO inicialices con README (ya tenemos uno)
#    → Copia la URL que te da GitHub (ej: https://github.com/TuUsuario/LSCTalker.git)

# 5. Conectar y subir
git remote add origin https://github.com/TuUsuario/LSCTalker.git
git branch -M main
git push -u origin main
```

---

## Compartir acceso (repo privado)

Para dar acceso a tu director o evaluadores sin hacerlo público:

1. Ve al repo en GitHub → **Settings → Collaborators**
2. Clic en **Add people**
3. Ingresa el usuario o email de GitHub de cada persona
4. Ellos recibirán una invitación por email

---

## Después de subir el APK

1. Genera el APK desde Android Studio (ver `apk/DEPOSITAR_APK_AQUI.md`)
2. Cópialo a la carpeta `apk/` con el nombre `LSCTalker_v1.0_debug.apk`
3. Haz un nuevo commit:

```powershell
git add apk/LSCTalker_v1.0_debug.apk
git commit -m "release: agrega APK debug v1.0"
git push
```

4. En GitHub, ve a **Releases → Create a new release** para publicarlo formalmente con tag `v1.0`
