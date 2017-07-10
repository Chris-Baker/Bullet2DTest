package com.base2.bullet2d;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.Collision;
import com.badlogic.gdx.physics.bullet.collision.ContactListener;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.collision.btBroadphaseInterface;
import com.badlogic.gdx.physics.bullet.collision.btCapsuleShape;
import com.badlogic.gdx.physics.bullet.collision.btCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btCollisionDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btCollisionObject;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btConeShape;
import com.badlogic.gdx.physics.bullet.collision.btCylinderShape;
import com.badlogic.gdx.physics.bullet.collision.btDbvtBroadphase;
import com.badlogic.gdx.physics.bullet.collision.btDefaultCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btSphereShape;
import com.badlogic.gdx.physics.bullet.dynamics.btConstraintSolver;
import com.badlogic.gdx.physics.bullet.dynamics.btDiscreteDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btSequentialImpulseConstraintSolver;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ArrayMap;
import com.badlogic.gdx.utils.Disposable;

public class Bullet2DTest extends ApplicationAdapter {

	final static short GROUND_FLAG = 1 << 8;
	final static short OBJECT_FLAG = 1 << 9;
	final static short ALL_FLAG = -1;

	class MyContactListener extends ContactListener {
		@Override
		public boolean onContactAdded (int userValue0, int partId0, int index0, boolean match0, int userValue1, int partId1, int index1, boolean match1) {
			return true;
		}
	}

	static class MyMotionState extends btMotionState {
		Matrix4 transform;

		@Override
		public void getWorldTransform (Matrix4 worldTrans) {
			worldTrans.set(transform);
		}

		@Override
		public void setWorldTransform (Matrix4 worldTrans) {
			transform.set(worldTrans);
		}
	}

	static class GameObject implements Disposable {
		public final btRigidBody body;
		public final MyMotionState motionState;
		public final Matrix4 transform;
		private final Vector3 translation;
		private final Quaternion rotation;

		public GameObject (btRigidBody.btRigidBodyConstructionInfo constructionInfo) {
			rotation = new Quaternion();
			translation = new Vector3();
			motionState = new MyMotionState();
			transform = new Matrix4();
			motionState.transform = transform;
			body = new btRigidBody(constructionInfo);
			body.setMotionState(motionState);
			body.setLinearFactor(new Vector3(1,1,0));
			body.setAngularFactor(new Vector3(0,0,1));
		}

		@Override
		public void dispose () {
			body.dispose();
			motionState.dispose();
		}

		public Vector3 getTranslation() {
			return transform.getTranslation(translation);
		}

		public Quaternion getRotation() {
			return transform.getRotation(rotation);
		}

		static class Constructor implements Disposable {
			public final btCollisionShape shape;
			public final btRigidBody.btRigidBodyConstructionInfo constructionInfo;
			private static Vector3 localInertia = new Vector3();

			public Constructor (btCollisionShape shape, float mass) {
				this.shape = shape;
				if (mass > 0f)
					shape.calculateLocalInertia(mass, localInertia);
				else
					localInertia.set(0, 0, 0);
				this.constructionInfo = new btRigidBody.btRigidBodyConstructionInfo(mass, null, shape, localInertia);
			}

			public GameObject construct () {
				return new GameObject(constructionInfo);
			}

			@Override
			public void dispose () {
				shape.dispose();
				constructionInfo.dispose();
			}
		}
	}

	OrthographicCamera cam;
	ModelBatch modelBatch;
	Environment environment;
	Model model;
	Array<GameObject> instances;
	ArrayMap<String, GameObject.Constructor> constructors;
	float spawnTimer;

	btCollisionConfiguration collisionConfig;
	btDispatcher dispatcher;
	MyContactListener contactListener;
	btBroadphaseInterface broadphase;
	btDynamicsWorld dynamicsWorld;
	btConstraintSolver constraintSolver;

	ShapeRenderer shapeRenderer;

	@Override
	public void create () {
		Bullet.init();

		modelBatch = new ModelBatch();
		environment = new Environment();
		environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f));
		environment.add(new DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f));

		cam = new OrthographicCamera(Gdx.graphics.getWidth() / 32, Gdx.graphics.getHeight() / 32);
		cam.position.set(0, 4, 10f);
		cam.near = 1f;
		cam.far = 300f;
		cam.update();

		constructors = new ArrayMap<String, GameObject.Constructor>(String.class, GameObject.Constructor.class);
		constructors.put("ground", new GameObject.Constructor(new btBoxShape(new Vector3(2.5f, 0.5f, 2.5f)), 0f));
		constructors.put("sphere", new GameObject.Constructor(new btSphereShape(0.5f), 1f));
		constructors.put("box", new GameObject.Constructor(new btBoxShape(new Vector3(0.5f, 0.5f, 0.5f)), 1f));
		//constructors.put("cone", new GameObject.Constructor(new btConeShape(0.5f, 2f), 1f));
		constructors.put("capsule", new GameObject.Constructor(new btCapsuleShape(.5f, 1f), 1f));
		constructors.put("cylinder", new GameObject.Constructor(new btCylinderShape(new Vector3(.5f, 1f, .5f)), 1f));

		collisionConfig = new btDefaultCollisionConfiguration();
		dispatcher = new btCollisionDispatcher(collisionConfig);
		broadphase = new btDbvtBroadphase();
		constraintSolver = new btSequentialImpulseConstraintSolver();
		dynamicsWorld = new btDiscreteDynamicsWorld(dispatcher, broadphase, constraintSolver, collisionConfig);
		dynamicsWorld.setGravity(new Vector3(0, -25f, 0));
		contactListener = new MyContactListener();

		instances = new Array<GameObject>();
		GameObject object = constructors.get("ground").construct();
		object.body.setCollisionFlags(object.body.getCollisionFlags() | btCollisionObject.CollisionFlags.CF_KINEMATIC_OBJECT);
		instances.add(object);
		dynamicsWorld.addRigidBody(object.body);
		object.body.setContactCallbackFlag(GROUND_FLAG);
		object.body.setContactCallbackFilter(0);
		object.body.setActivationState(Collision.DISABLE_DEACTIVATION);

		shapeRenderer = new ShapeRenderer();
	}

	public void spawn () {
		GameObject obj = constructors.values[1 + MathUtils.random(constructors.size - 2)].construct(); // obj = constructors.get("cylinder").construct();
		obj.transform.trn(0, 9f, 0);
		obj.body.proceedToTransform(obj.transform);
		obj.body.setUserValue(instances.size);
		obj.body.setCollisionFlags(obj.body.getCollisionFlags() | btCollisionObject.CollisionFlags.CF_CUSTOM_MATERIAL_CALLBACK);
		instances.add(obj);
		dynamicsWorld.addRigidBody(obj.body);
		obj.body.setContactCallbackFlag(OBJECT_FLAG);
		obj.body.setContactCallbackFilter(GROUND_FLAG);
	}

	float angle, speed = 90f;

	@Override
	public void render () {
		final float delta = Math.min(1f / 30f, Gdx.graphics.getDeltaTime());

		angle = (angle + delta * speed) % 360f;
		instances.get(0).transform.setTranslation(0, MathUtils.sinDeg(angle) * 2.5f, 0f);

		dynamicsWorld.stepSimulation(delta, 5, 1f / 60f);

		if ((spawnTimer -= delta) < 0) {
			spawn();
			spawnTimer = 1.5f;
		}

		Gdx.gl.glClearColor(0.3f, 0.3f, 0.3f, 1.f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

		shapeRenderer.setProjectionMatrix(cam.combined);
		shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
		shapeRenderer.setColor(0, 1, 0, 1);
		for (GameObject instance: instances) {
			btCollisionShape shape = instance.body.getCollisionShape();

			float x = instance.getTranslation().x;
			float y = instance.getTranslation().y;

			shapeRenderer.identity();

			if (shape instanceof btBoxShape) {
				btBoxShape boxShape = (btBoxShape) shape;
				float width = boxShape.getHalfExtentsWithMargin().x;
				float height = boxShape.getHalfExtentsWithMargin().y;

				shapeRenderer.translate(x, y, 0);
				shapeRenderer.rotate(0, 0, 1, instance.getRotation().getAngleAround(0, 0, 1));
				shapeRenderer.rect(-width, -height, width * 2, height * 2);
			}
			else if (shape instanceof btSphereShape) {
				btSphereShape sphereShape = (btSphereShape) shape;
				float radius = sphereShape.getRadius();
				shapeRenderer.circle(x, y, radius, 16);
			}
			else if (shape instanceof btCylinderShape) {
				btCylinderShape cylinderShape = (btCylinderShape) shape;
				float width = cylinderShape.getRadius();
				float height = cylinderShape.getHalfExtentsWithMargin().y;

				shapeRenderer.translate(x, y, 0);
				shapeRenderer.rotate(0, 0, 1, instance.getRotation().getAngleAround(0, 0, 1));
				shapeRenderer.rect(-width, -height, width * 2, height * 2);
			}
			else if (shape instanceof btCapsuleShape) {
				btCapsuleShape capsuleShape = (btCapsuleShape) shape;
				float width = capsuleShape.getRadius();
				float height = capsuleShape.getHalfHeight();

				shapeRenderer.translate(x, y, 0);
				shapeRenderer.rotate(0, 0, 1, instance.getRotation().getAngleAround(0, 0, 1));
				shapeRenderer.circle(0, height, width, 16);
				shapeRenderer.rect(-width, -height, width * 2, height * 2);
				shapeRenderer.circle(0, -height, width, 16);
			}
		}
		shapeRenderer.end();
	}

	@Override
	public void dispose () {
		for (GameObject obj : instances)
			obj.dispose();
		instances.clear();

		for (GameObject.Constructor ctor : constructors.values())
			ctor.dispose();
		constructors.clear();

		dynamicsWorld.dispose();
		constraintSolver.dispose();
		broadphase.dispose();
		dispatcher.dispose();
		collisionConfig.dispose();

		contactListener.dispose();

		shapeRenderer.dispose();
	}
}
