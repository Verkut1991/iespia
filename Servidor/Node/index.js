const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const { MongoClient } = require('mongodb');

// Importación de Módulos de Rutas 
// Cada módulo de ruta es un Express Router independiente.
const enrutadorAutenticacion = require('./rutas/autenticacion');
const enrutadorNuevoObjetivo = require('./rutas/nuevo_objetivo');
const enrutadorEscaneo = require('./rutas/scan');

// Configuracion de Base de Datos (URI y nombre via entorno; localhost por defecto).
const URI_MONGO = process.env.MONGODB_URI || 'mongodb://localhost:27017';
const NOMBRE_BD = process.env.MONGODB_DB || 'iespia_demo';

// Cliente de MongoDB.
const cliente = new MongoClient(URI_MONGO);
let coleccionAuditoria; // Variable para almacenar la referencia a la colección de auditoría.

/**
 * Conecta a MongoDB e inicializa la coleccion de auditoria.
 */
async function conectarAMongo() {
    try {
        await cliente.connect();
        console.log(`[DB] Conectado a MongoDB - ${NOMBRE_BD}`);
        const bd = cliente.db(NOMBRE_BD);
        coleccionAuditoria = bd.collection('Auditoria_iEspia');
    } catch (err) {
        console.error('[DB] Error de conexión:', err);
    }
}
// Inicia la conexión a la base de datos al arrancar el servidor.
conectarAMongo();

// Configuración del Servidor Express y Socket.IO 
const aplicacion = express();
const servidor = http.createServer(aplicacion);

// Inicializa Socket.IO permitiendo conexiones desde cualquier origen (CORS).
const io = new Server(servidor, {
    cors: {
        origin: "*", // Permite conexiones desde cualquier dominio (útil para desarrollo/apps móviles)
        methods: ["GET", "POST"] // Métodos HTTP permitidos para el handshake
    }
});

// Puerto donde escuchará el servidor.
const PUERTO = process.env.PORT || 6010;

// Almacenamiento en Memoria (Volátil) 
let socketsActivos = new Map(); // Mapa para asociar ID de agente -> Conexión Socket (para enviar notificaciones directas).

// Middleware Global 
aplicacion.use(cors()); // Habilita CORS para todas las rutas.
aplicacion.use(express.json({ limit: '50mb' })); // Permite recibir JSON grandes (necesario para imágenes en Base64).

// Middleware de Auditoría 
// Este middleware intercepta todas las peticiones para registrarlas en la base de datos.
aplicacion.use((req, res, next) => {
    const inicio = Date.now(); // Marca de tiempo inicial.

    // Escucha el evento 'finish' de la respuesta (cuando se ha enviado al cliente).
    res.on('finish', () => {
        const duracion = Date.now() - inicio; // Calcula cuánto tardó la petición.

        // Intenta obtener el ID del agente si está autenticado o viene en el cuerpo.
        const idAgente = req.user ? req.user.agentId : (req.body ? req.body.agentId : null);

        // Objeto con los datos del log.
        const entradaLog = {
            ip: req.ip, // Dirección IP del cliente.
            method: req.method, // Método HTTP (GET, POST, etc.).
            path: req.path, // Ruta solicitada.
            statusCode: res.statusCode, // Código de respuesta (200, 401, 500...).
            agentId: idAgente || 'anonymous', // ID del agente o 'anonymous' si no se identifica.
            duration: duracion, // Tiempo de procesamiento en ms.
            timestamp: new Date() // Fecha y hora del evento.
        };

        // Si la colección de auditoría está lista, guarda el log.
        if (coleccionAuditoria) {
            coleccionAuditoria.insertOne(entradaLog)
                .then(() => console.log(`[AUDIT DB] Guardado: ${entradaLog.method} ${entradaLog.path} ${entradaLog.statusCode}`))
                .catch(err => console.error('[AUDIT DB] Error guardando log:', err));
        }
    });

    // Continúa con el siguiente middleware o ruta.
    next();
});

//  Montaje de Rutas 
// Cada router se monta en su prefijo correspondiente.
// Las rutas internas del router se combinan con el prefijo:
//   /auth + /login  => /auth/login
//   /targets + /    => /targets
//   /recognition/scan + / => /recognition/scan
aplicacion.use('/auth', enrutadorAutenticacion.router);
aplicacion.use('/targets', enrutadorNuevoObjetivo.router);
aplicacion.use('/recognition/scan', enrutadorEscaneo.router);

//  Notificaciones en Tiempo Real 

/**
 * Envía una notificación en tiempo real a un agente específico mediante Socket.IO.
 */
function enviarNotificacion(idAgente, cargaUtil) {
    // Busca el socket activo para ese agente.
    const socket = socketsActivos.get(idAgente);
    if (socket && socket.connected) {
        socket.emit('notification', cargaUtil); // Emite el evento 'notification'.
    } else {
        console.log(`[Socket.IO] Agente ${idAgente} no conectado, notificación descartada.`);
        // Aquí se podría implementar una cola o notificaciones push como respaldo.
    }
}

// Inyecta la función enviarNotificacion en el módulo de scan para que pueda enviar alertas.
enrutadorEscaneo.establecerEnviarNotificacion(enviarNotificacion);

//  Socket.IO 
// Manejo de conexiones WebSocket.
io.on('connection', (socket) => {
    console.log(`[Socket.IO] Cliente conectado: ${socket.id}`);

    // Evento 'register': El agente debe identificarse con su ID.
    socket.on('register', (datos) => {
        const { agentId } = datos;
        if (agentId) {
            console.log(`[Socket.IO] Agente registrado: ${agentId}`);
            // Guarda la asociación agentId -> socket.
            socketsActivos.set(agentId, socket);
            socket.agentId = agentId; // Guarda el ID en el objeto socket para usarlo al desconectar.

            // Envía mensaje de confirmación.
            socket.emit('connected', { type: 'CONNECTED', message: 'Canal seguro establecido' });
        }
    });

    socket.on('message', (mensaje) => {
        console.log(`[Socket.IO] Recibido: ${JSON.stringify(mensaje)}`);
    });

    // Manejo de desconexión.
    socket.on('disconnect', () => {
        if (socket.agentId) {
            console.log(`[Socket.IO] Agente desconectado: ${socket.agentId}`);
            // Elimina al agente del mapa de sockets activos.
            socketsActivos.delete(socket.agentId);
        } else {
            console.log(`[Socket.IO] Cliente desconectado: ${socket.id}`);
        }
    });
});

// Inicia el servidor HTTP (escuchando tanto Express como Socket.IO).
servidor.listen(PUERTO, () => {
    console.log(`[iEspia] Servidor ejecutándose en puerto ${PUERTO}`);
});
