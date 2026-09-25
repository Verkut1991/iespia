const express = require('express');
const router = express.Router();
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const rateLimit = require('express-rate-limit');

//  Configuración JWT 

// Clave secreta para firmar los tokens JWT.
// Si existe una variable de entorno JWT_SECRET se usa esa, si no, se usa una por defecto.
const CLAVE_SECRETA_JWT = process.env.JWT_SECRET || 'change-me-local-demo';

// Tiempo de expiración por defecto para las claves de API.
// Se ha establecido en 30 días ('30d').
const EXPIRACION_CLAVE_API = '30d';

//  Funciones de Autenticación 

/**
 * Genera un token JWT para un agente.
 */
const generarToken = (cargaUtil, expiraEn = EXPIRACION_CLAVE_API) => {
    // Utiliza la librería jsonwebtoken para firmar el objeto payload
    // con nuestra clave secreta y la expiración configurada.
    return jwt.sign(cargaUtil, CLAVE_SECRETA_JWT, { expiresIn: expiraEn });
};

/**
 * Middleware para autenticar las peticiones entrantes usando JWT.
 * Se ejecuta antes que los controladores de las rutas protegidas.
 */
const autenticar = (req, res, next) => {
    // Obtiene el encabezado 'Authorization' de la petición HTTP.
    const cabeceraAuth = req.headers['authorization'];

    // El formato esperado es 'Bearer <TOKEN>'.
    // Si existe el encabezado, lo divide por el espacio y toma la segunda parte (el token).
    const token = cabeceraAuth && cabeceraAuth.split(' ')[1];

    // Si no se encuentra el token, devuelve un error 401 (No autorizado).
    if (!token) {
        return res.status(401).json({ error: 'No autorizado: No se proporcionó token' });
    }

    // Verifica la validez del token usando la clave secreta.
    jwt.verify(token, CLAVE_SECRETA_JWT, (err, decodificado) => {
        // Si hay un error (token inválido, modificado o expirado), devuelve 401.
        if (err) {
            return res.status(401).json({ error: 'No autorizado: Token inválido o expirado' });
        }

        // Si el token es válido, guarda los datos decodificados (payload) en el objeto request.
        // Esto permite que las siguientes funciones accedan a la información del usuario (ej. agentId).
        req.user = decodificado;

        // Pasa el control a la siguiente función middleware o controlador.
        next();
    });
};

/**
 * Renueva un token existente, extendiendo su validez.
 * Permite obtener un nuevo token válido a partir de uno caducado recientemente.
 */
const renovarToken = (tokenAntiguo) => {
    try {
        // Verifica el token antiguo ignorando su fecha de expiración.
        // Esto permite renovar tokens que acaban de caducar.
        const decodificado = jwt.verify(tokenAntiguo, CLAVE_SECRETA_JWT, { ignoreExpiration: true });

        // Elimina los campos técnicos del JWT anterior del objeto decodificado.
        // 'iat' (issued at): fecha de emisión original.
        // 'exp' (expiration): fecha de expiración original.
        // 'nbf' (not before): fecha de inicio de validez.
        // 'jti' (jwt id): identificador único del token.
        delete decodificado.iat;
        delete decodificado.exp;
        delete decodificado.nbf;
        delete decodificado.jti;

        // Genera un nuevo token con los datos limpios y un nuevo tiempo de expiración estándar.
        // El nuevo token tendrá otros 30 días de validez desde este momento.
        return generarToken(decodificado, EXPIRACION_CLAVE_API);
    } catch (err) {
        // Si el token no es válido (firma incorrecta, malformado), se captura el error.
        console.error("Fallo al renovar el token:", err.message);
        return null;
    }
};

/**
 * Provee (crea) una clave de API de larga duración automáticamente.
 * Se usa cuando un agente se registra o solicita acceso por primera vez.
 */
const proveerClaveApi = (idAgente) => {
    // Genera un token incluyendo el ID del agente, su rol y el tipo de token.
    const token = generarToken({ agentId: idAgente, role: 'agent', type: 'api_key' }, EXPIRACION_CLAVE_API);

    // Devuelve el token junto con la fecha de emisión y el tiempo de validez.
    return {
        apiKey: token,
        issuedAt: new Date(),
        expiresIn: EXPIRACION_CLAVE_API
    };
};

//  Limitador de Tasa (Rate Limiting) para Autenticación 
// Protege contra ataques de fuerza bruta limitando los intentos de login.
const limitadorAuth = rateLimit({
    windowMs: 15 * 60 * 1000, // Ventana de tiempo: 15 minutos.
    max: 100, // Máximo 100 peticiones por IP dentro de esa ventana.
    message: 'Demasiados intentos de autenticación, por favor intente más tarde.',
    standardHeaders: true, // Devuelve info de límites en las cabeceras `RateLimit-*`.
    legacyHeaders: false, // Deshabilita las cabeceras `X-RateLimit-*` antiguas.
});

//  Rutas de Autenticación 

// Ruta para hacer login (simulado para obtener token inicial).
// Aplica el limitador de tasa (limitadorAuth).
router.post('/login', limitadorAuth, (req, res) => {
    // En un caso real, aquí se verificaría usuario/contraseña.
    // Si no se envía agentId, genera uno nuevo UUID.
    const idAgente = (req.body && req.body.agentId) || uuidv4();

    // Genera un token JWT para este agente.
    const token = generarToken({ agentId: idAgente, role: 'agent' });

    // Devuelve el token y su tiempo de expiración.
    res.json({ token, expiresIn: EXPIRACION_CLAVE_API });
});

// Ruta para renovar un token caducado o próximo a caducar.
// Requiere autenticación (aunque el token haya expirado hace poco, la lógica lo maneja).
router.post('/renew', limitadorAuth, autenticar, (req, res) => {
    // Extrae el token "viejo" de la cabecera Authorization.
    const tokenAntiguo = req.headers['authorization'].split(' ')[1];

    // Llama a la lógica de renovación.
    const nuevoToken = renovarToken(tokenAntiguo);

    if (nuevoToken) {
        console.log(` Token renovado para agente: ${req.user.agentId}`);
        res.json({ token: nuevoToken, expiresIn: EXPIRACION_CLAVE_API });
    } else {
        // Si no se puede renovar (ej. token manipulado o expirado hace demasiado), devuelve 401.
        res.status(401).json({ error: 'Fallo al renovar el token' });
    }
});

// Nueva Ruta: Generación Automática de API Key.
// Permite obtener credenciales de larga duración.
router.post('/keygen', limitadorAuth, (req, res) => {
    const idAgente = (req.body && req.body.agentId) || `agent-${uuidv4().substring(0, 8)}`;
    const datosClaveApi = proveerClaveApi(idAgente);

    console.log(` API Key automática generada para: ${idAgente}`);
    res.json({
        agentId: idAgente,
        ...datosClaveApi // Expande el objeto devuelto por proveerClaveApi (apiKey, issuedAt, expiresIn).
    });
});

// Exporta el router y el middleware de autenticación para que otros módulos lo usen.
module.exports = {
    router,
    autenticar,
    generarToken,
    renovarToken,
    proveerClaveApi,
    EXPIRACION_CLAVE_API,
    CLAVE_SECRETA_JWT
};
